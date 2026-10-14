package app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;

/** Single-threaded NIO event loop: accept, read, write with backpressure. */
final class Server {
    /** Consume one complete request already framed by Http; write the response into out. */
    interface Handler {
        void handle(Conn c, Http.Request r, ByteBuffer out);

        /**
         * Runs on the loop thread after every select batch that handled at least one key: commits the batch.
         * False means the commit failed and the deferred connections must be dropped without a response.
         */
        default boolean tick() { return true; }

        /** Runs after the deferred responses were released (checkpoints and the like). */
        default void afterTick() {}
    }

    private static final byte[] R400 = ascii("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    private static final byte[] R413 = ascii("HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    private static final int OUT_CAP = 256 * 1024;
    private static final int OUT_FLUSH_AT = 64 * 1024; // flush mid-loop when less than this is free

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static volatile int open;

    static int openConnections() {
        return open;
    }

    static final class Conn {
        final SocketChannel ch;
        final SelectionKey key;
        byte[] partial;
        int partialLen;
        ByteBuffer pending;
        long lastActive;
        boolean closeAfterWrite;
        Conn prev, next; // intrusive LRU links (head = least recently active)
        boolean deferred; // response held back until the tick's COMMIT returned (set by the Router on a write)
        boolean queued;   // on the deferred list
        Conn deferNext;

        Conn(SocketChannel ch, SelectionKey key) {
            this.ch = ch;
            this.key = key;
        }
    }

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final ByteBuffer readBuf = ByteBuffer.allocateDirect(64 * 1024);
    private final ByteBuffer out = ByteBuffer.allocateDirect(OUT_CAP); // reused for every response batch
    private final byte[] work = new byte[64 * 1024];                   // read bytes as heap array for the parser
    private final Http.Request req = new Http.Request();
    private final Handler handler;
    private int openCount; // loop-thread counter, mirrored to the volatile
    private final int maxConns;
    private Conn lruHead, lruTail;
    private Conn deferredHead, deferredTail;

    /** Cap derived from the fd limit: nofile - 512, clamped to [1024, 1_000_000]; 60_000 when unknown. */
    static int defaultMaxConns() {
        long n = Os.nofileSoftLimit();
        if (n < 0) return 60_000;
        return (int) Math.max(1024, Math.min(1_000_000, n - 512));
    }

    Server(String host, int port, int backlog, Handler handler, int maxConns) throws IOException {
        this.handler = handler;
        this.maxConns = maxConns;
        selector = Selector.open();
        listener = ServerSocketChannel.open();
        listener.configureBlocking(false);
        listener.bind(new InetSocketAddress(host, port), backlog);
        listener.register(selector, SelectionKey.OP_ACCEPT);
    }

    int port() throws IOException {
        return ((InetSocketAddress) listener.getLocalAddress()).getPort();
    }

    void run() {
        while (true) {
            try {
                if (selector.select(this::onKey) > 0) {
                    boolean ok = handler.tick();
                    releaseDeferred(ok);
                    handler.afterTick();
                }
            } catch (Throwable t) {
                // the loop never dies
            }
        }
    }

    /** After the tick's COMMIT: ok sends every held response, otherwise the connections are dropped with an RST. */
    private void releaseDeferred(boolean ok) {
        Conn c = deferredHead;
        deferredHead = deferredTail = null;
        while (c != null) {
            Conn next = c.deferNext;
            c.deferNext = null;
            c.queued = false;
            c.deferred = false;
            try {
                if (c.key.attachment() != null) {
                    if (!ok) evict(c);
                    else if (c.pending != null) drainDeferred(c);
                    else if (c.closeAfterWrite) close(c);
                }
            } catch (Throwable t) {
                close(c);
            }
            c = next;
        }
    }

    /** Writes the held response; a short write parks the rest under OP_WRITE like flush does. */
    private void drainDeferred(Conn c) throws IOException {
        ByteBuffer p = c.pending;
        c.ch.write(p);
        if (p.hasRemaining()) {
            c.key.interestOps(SelectionKey.OP_WRITE);
            return;
        }
        c.pending = null;
        if (c.closeAfterWrite) close(c);
    }

    /** Appends the shared out buffer to the connection's held response (only when it is not empty). */
    private void hold(Conn c) {
        out.flip();
        int n = out.remaining();
        if (n > 0) {
            ByteBuffer p = c.pending;
            if (p == null) {
                p = ByteBuffer.allocate(n);
            } else {
                ByteBuffer q = ByteBuffer.allocate(p.remaining() + n);
                q.put(p);
                p = q;
            }
            p.put(out);
            p.flip();
            c.pending = p;
        }
        out.clear();
        if (!c.queued) {
            c.queued = true;
            if (deferredTail != null) deferredTail.deferNext = c; else deferredHead = c;
            deferredTail = c;
        }
    }

    private void onKey(SelectionKey key) {
        if (key.channel() == listener) {
            accept();
            return;
        }
        Conn c = (Conn) key.attachment();
        if (c == null) return;
        try {
            if (!key.isValid()) {
                close(c);
                return;
            }
            if (key.isWritable()) drain(c);
            if (key.isValid() && c.pending == null && key.isReadable()) read(c);
        } catch (Throwable t) {
            close(c);
        }
    }

    private void accept() {
        try {
            SocketChannel ch;
            while ((ch = listener.accept()) != null) {
                try {
                    while (openCount >= maxConns && lruHead != null) evict(lruHead);
                    ch.configureBlocking(false);
                    ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    SelectionKey k = ch.register(selector, 0);
                    Conn c = new Conn(ch, k);
                    k.attach(c);
                    k.interestOps(SelectionKey.OP_READ);
                    c.lastActive = System.nanoTime();
                    touch(c);
                    openCount++;
                    open = openCount;
                } catch (Throwable t) {
                    try { ch.close(); } catch (IOException ignored) { }
                }
            }
        } catch (Throwable t) {
            // accept failure (e.g. fd exhaustion): keep looping
        }
    }

    private void touch(Conn c) {
        if (lruTail == c) return;
        unlink(c);
        c.prev = lruTail;
        if (lruTail != null) lruTail.next = c; else lruHead = c;
        lruTail = c;
    }

    private void unlink(Conn c) {
        if (c.prev != null) c.prev.next = c.next; else if (lruHead == c) lruHead = c.next;
        if (c.next != null) c.next.prev = c.prev; else if (lruTail == c) lruTail = c.prev;
        c.prev = null;
        c.next = null;
    }

    /** Drops a connection with an RST (linger 0: no TIME_WAIT). */
    private void evict(Conn c) {
        try { c.ch.setOption(StandardSocketOptions.SO_LINGER, 0); } catch (Throwable ignored) { }
        close(c);
    }

    private void close(Conn c) {
        if (c.key.attachment() == null) return;
        c.key.attach(null);
        unlink(c);
        c.key.cancel();
        try { c.ch.close(); } catch (IOException ignored) { }
        c.pending = null;
        c.partial = null;
        openCount--;
        open = openCount;
    }

    private void read(Conn c) throws IOException {
        readBuf.clear();
        int n = c.ch.read(readBuf);
        if (n <= 0) {
            close(c);
            return;
        }
        c.lastActive = System.nanoTime();
        touch(c);
        readBuf.flip();
        if (c.partialLen == 0) {
            readBuf.get(work, 0, n);
            process(c, work, n);
        } else {
            int need = c.partialLen + n;
            if (c.partial.length < need) c.partial = java.util.Arrays.copyOf(c.partial, Math.max(need, c.partial.length * 2));
            readBuf.get(c.partial, c.partialLen, n);
            c.partialLen = need;
            process(c, c.partial, need);
        }
    }

    /**
     * Frames buf[0..end) into requests (pipelining), appends all responses to the shared out buffer
     * and writes it once. The unconsumed tail goes to c.partial.
     */
    private void process(Conn c, byte[] buf, int end) throws IOException {
        int off = 0;
        out.clear();
        boolean stop = false;
        while (off < end && !stop) {
            int n = Http.parse(buf, off, end - off, req);
            if (n == Http.NEED_MORE) break;
            if (n == Http.MALFORMED) {
                out.put(R400);
                c.closeAfterWrite = true;
                off = end;
                break;
            }
            if (n == Http.TOO_LARGE) {
                out.put(R413);
                c.closeAfterWrite = true;
                off = end;
                break;
            }
            handler.handle(c, req, out);
            off += n;
            if (c.deferred && out.remaining() < OUT_FLUSH_AT) hold(c); // nothing may reach the socket before COMMIT
            if (!req.keepAlive) {
                c.closeAfterWrite = true;
                off = end;
                break;
            }
            if (!c.deferred && out.remaining() < OUT_FLUSH_AT) {
                // large pipelined batch: flush now; if the socket is full, park the rest until drained
                if (!flush(c)) stop = true;
            }
        }
        // keep the unconsumed tail
        int rest = end - off;
        if (rest > 0) {
            if (buf == c.partial) {
                if (off > 0) System.arraycopy(buf, off, buf, 0, rest);
            } else {
                if (c.partial == null || c.partial.length < rest) c.partial = new byte[Math.max(rest, 256)];
                System.arraycopy(buf, off, c.partial, 0, rest);
            }
        }
        c.partialLen = rest;
        if (c.deferred) hold(c);
        else if (!stop) flush(c);
    }

    /** Writes the shared out buffer. Returns true when fully written (buffer reset), false when parked as pending. */
    private boolean flush(Conn c) throws IOException {
        out.flip();
        if (!out.hasRemaining()) {
            out.clear();
            if (c.closeAfterWrite) close(c);
            return true;
        }
        c.ch.write(out);
        if (out.hasRemaining()) {
            ByteBuffer p = ByteBuffer.allocateDirect(out.remaining()); // copy: the shared buffer stays reusable
            p.put(out).flip();
            out.clear();
            c.pending = p;
            c.key.interestOps(SelectionKey.OP_WRITE);
            return false;
        }
        out.clear();
        if (c.closeAfterWrite) close(c);
        return true;
    }

    private void drain(Conn c) throws IOException {
        ByteBuffer p = c.pending;
        if (p == null) return;
        c.ch.write(p);
        if (p.hasRemaining()) return;
        c.pending = null;
        if (c.closeAfterWrite) {
            close(c);
            return;
        }
        c.key.interestOps(SelectionKey.OP_READ);
        if (c.partialLen > 0) process(c, c.partial, c.partialLen); // requests parked behind the short write
    }
}
