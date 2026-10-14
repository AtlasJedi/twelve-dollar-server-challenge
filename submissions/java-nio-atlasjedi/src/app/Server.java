package app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;

/** Single-threaded NIO event loop: accept, read, write with backpressure. */
final class Server {
    /** Temporary line-echo mode (Task 3). Task 4 replaces the framing with Http. */
    static boolean echoMode = false;
    static int echoRepeat = 1;

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

        Conn(SocketChannel ch, SelectionKey key) {
            this.ch = ch;
            this.key = key;
        }
    }

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final ByteBuffer readBuf = ByteBuffer.allocateDirect(64 * 1024);
    private int openCount; // loop-thread counter, mirrored to the volatile

    Server(String host, int port, int backlog) throws IOException {
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
                selector.select(this::onKey);
            } catch (Throwable t) {
                // the loop never dies
            }
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
                    ch.configureBlocking(false);
                    ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    SelectionKey k = ch.register(selector, 0);
                    Conn c = new Conn(ch, k);
                    k.attach(c);
                    k.interestOps(SelectionKey.OP_READ);
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

    private void close(Conn c) {
        if (c.key.attachment() == null) return;
        c.key.attach(null);
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
        readBuf.flip();
        ByteBuffer out = frame(c, readBuf);
        if (out != null) write(c, out);
    }

    /**
     * FRAMING (temporary, Task 3): splits on '\n' and echoes each line echoRepeat times.
     * Task 4 replaces this with Http parsing + Handler. Returns the response bytes or null.
     */
    private ByteBuffer frame(Conn c, ByteBuffer in) {
        int have = in.remaining();
        byte[] data = new byte[c.partialLen + have];
        if (c.partialLen > 0) System.arraycopy(c.partial, 0, data, 0, c.partialLen);
        in.get(data, c.partialLen, have);
        int end = data.length;
        int last = -1;
        for (int i = end - 1; i >= 0; i--) {
            if (data[i] == '\n') { last = i; break; }
        }
        int rest = end - (last + 1);
        if (rest > 0) {
            if (c.partial == null || c.partial.length < rest) c.partial = new byte[Math.max(rest, 256)];
            System.arraycopy(data, last + 1, c.partial, 0, rest);
        }
        c.partialLen = rest;
        if (last < 0) return null;
        int repeat = echoMode ? echoRepeat : 1;
        int framed = last + 1;
        ByteBuffer out = ByteBuffer.allocateDirect(framed * repeat);
        int start = 0;
        for (int i = 0; i < framed; i++) {
            if (data[i] == '\n') {
                for (int r = 0; r < repeat; r++) out.put(data, start, i + 1 - start);
                start = i + 1;
            }
        }
        out.flip();
        return out;
    }

    private void write(Conn c, ByteBuffer out) throws IOException {
        c.ch.write(out);
        if (out.hasRemaining()) {
            c.pending = out;
            c.key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void drain(Conn c) throws IOException {
        ByteBuffer p = c.pending;
        if (p == null) return;
        c.ch.write(p);
        if (!p.hasRemaining()) {
            c.pending = null;
            c.key.interestOps(SelectionKey.OP_READ);
        }
    }
}
