# java-nio

A single-threaded HTTP/1.1 server for the $12 Server Challenge, written in plain Java 25 with no framework and no
third-party dependency. One event-loop thread on a `java.nio` `Selector` parses requests by hand, talks to SQLite 3.54.0
through the JDK's Panama FFM API, writes the JSON by hand, and holds 60,000 idle keep-alive connections in about 325 MB RSS
on the Docker test rig. It binds the port itself (no Nginx).

## Language, framework, driver, versions

| | |
|---|---|
| Language | Java 25 (Temurin 25.0.4.1+1, installed by `install.sh` into `/opt/jdk-25`, sha256-checked, picked by `uname -m`) |
| Framework | none (`java.nio` Selector, hand-written HTTP/1.1 parser, JSON writer and HS256 verifier) |
| Driver | none: Panama FFM downcalls into SQLite 3.54.0, built by `build.sh` from the pinned amalgamation (sha3-256 verified) |
| Dependencies | zero third-party; only the JDK tarball and the SQLite source are downloaded |

## Mode: direct

The server binds `HOST:PORT` itself (backlog 4096), no Nginx. Nginx would spend part of the only core on every request and
cap the connection count at its `worker_connections`; with a single event loop already in front of the database a proxy adds
a copy and a context switch per request and nothing else. Connections are never closed for being idle.

## Architecture

- One event-loop thread on a `java.nio` `Selector` does accept, read, parse, route, query and write. There is no worker
  pool and no lock; the box has one vCPU.
- Hand-written HTTP/1.1 framer (keep-alive, pipelining, split requests, body limit), hand-written JSON writer (exact bytes,
  fixed key order), strict body parser, HS256 JWT verifier. Warm-path allocation is about 18 bytes per request.
- One SQLite connection in `EXCLUSIVE` WAL mode (`synchronous=NORMAL`, 1 GiB `mmap_size`, 16 MiB page cache,
  `wal_autocheckpoint=0`, `foreign_keys=ON`), statements prepared once.
- Group commit: the writes of one select batch share one `BEGIN`/`COMMIT`. A 201 or 200 for a write is held per
  connection and released only after `COMMIT` returned (rule 6); a failed commit rolls back and drops those connections
  without a response.
- No caching of any kind across requests (rule 5): every response is built from a query run for that request.

## Optimizations and why

Every effect is measured on the Docker rig on 2026-10-14 (see Numbers for the rig). Section ids refer to the measurement log
`MEASUREMENTS.md` in this folder.

| Optimization | Why | Measured effect | MEASUREMENTS.md section |
|---|---|---|---|
| Group commit per loop tick, responses released after COMMIT | one WAL append per batch instead of one per write | +11.8 % req/s (78,794 vs 70,500, equal-footing re-run), p99 2.26 vs 2.44 ms | `ab-group-commit` |
| Zero-allocation column copy (one global address-space segment, native-address text reads, direct copy into the response buffer) | FFM `reinterpret`/`ofBuffer` views were 99.8 % of the per-request garbage | allocation 2.0 KB to about 18 B per request (-99 %), GC pauses 203 to 2 per 90 s, +3.7 % req/s | `ab-json` |
| `INSERT` + `created_at` read by rowid instead of `RETURNING` | `RETURNING` materialises its rows in an ephemeral table | create path 8,375 ns to 1,084 ns median (-87 %), no saturation regression | `ab-create` |
| PGO-compiled SQLite (gcc `-fprofile-generate`, `app.Train`, `-fprofile-use`) | the hot SQLite paths are the same few statements | +6.9 % req/s (84,571 vs 79,135), p99 2.16 vs 2.28 ms; build 26.7 s on 1 CPU | `ab-pgo` |
| Checkpoints on the loop tick (`wal_autocheckpoint=0`, PASSIVE after 1,000 committed writes) | a checkpoint inside a commit runs on the request path and shows up in p99 | not A/B-measured; `CheckpointTest` proves none runs inside a request and the WAL stops growing | `baseline-sat` (reference) |
| fd cap from `RLIMIT_NOFILE`, LRU eviction by RST (`SO_LINGER 0`) | the process must survive the fd limit without accept failures or TIME_WAIT pile-up | 60,000 held, 0 errors (cap 65,023 with `nofile=65535`); `CapTest` | `baseline-idle-60k` |
| SerialGC, `-Xms256m -Xmx512m`, `AlwaysPreTouch` | smallest footprint, no concurrent GC threads on one core | chosen over Parallel/ZGC by measurement (below) | `ab-gc` |
| `mmap_size` 1 GiB with a small page cache | reads served from the OS page cache without a copy | not A/B-measured | n/a |
| Pipelining into one shared output buffer | one `write` per read batch for pipelined clients | `RobustTest`, 42/42 | `baseline-sat` (reference) |
| Backpressure on short writes (`OP_WRITE`, reads from that connection paused) | a client that never reads must not grow server memory | at most one parked `pending` and one `partial` buffer per connection; `RobustTest` | `durability` (rule 6 check) |

Measured and rejected (no flags, no dead code left):

| Variant | Result | MEASUREMENTS.md section |
|---|---|---|
| ParallelGC | +0.7 % req/s best run (77,025 vs 76,457), RSS 345 vs 328 MB at 60k idle; below the 3 % bar | `ab-gc` |
| ZGC | -3.1 % req/s (74,102), RSS 474 vs 328 MB at 60k idle; idle p99 7 ms vs 67 ms and sub-0.1 ms pauses were not enough | `ab-gc` |
| JSON built in SQL (`json_group_array`/`json_object`) | byte-identical but 31 % slower (52,903 vs 79,267 req/s), p99 3.14 ms | `ab-json` |
| Virtual threads, one per connection | 18.5 % slower (70,498 vs 86,490 req/s); heap exhausted at about 30,000 connections, 37,207 of 60,000 refused | `ab-virtual-threads` |

The keep rule was: keep a variant only if it improves req/s or CPU by 3 % or more at equal or better p99 with all 42 checks
green.

## Numbers

All rows measured on 2026-10-14 on the final code (branch `feature/java-nio`, cp-20), 42/42 checks green. **None of these is
the DigitalOcean droplet.** The Docker rows run on an Apple M5 core inside a Docker Desktop VM that is several times faster
than a shared droplet vCPU, with the load generators on the same machine; read them as relative to each other and to the
baseline, not as predictions of the droplet. Row-per-rig: further rigs are appended below.

| Rig | Date | Workload | req/s | p95 ms | p99 ms | CPU % | RSS MB | Errors |
|---|---|---|---|---|---|---|---|---|
| Rig: Docker VM M5 / 1 CPU 2 GB container (arm64) | 2026-10-14 | saturation, 64 VUs, 90 s, best of 2 | 87,809 | n/a | 2.20 | 89.9 | 379 | 0 |
| Rig: Docker VM M5 / 1 CPU 2 GB container (arm64) | 2026-10-14 | ladder, 20,000 users (k6 `load.js`, ramp 60 s + 2 min) | 1,663 | 0.87 | 4.05 | 14.0 mean / 23.3 max | 348 | 0 |
| Rig: Docker VM M5 / 1 CPU 2 GB container (arm64) | 2026-10-14 | 60,000 idle keep-alive connections held 5 min, 1.83 M requests | 6,100 (idle poll) | n/a | 2.70 | 45.4 | 325 | 0 |
| Rig: Hetzner CPX11 x86_64, 2 vCPU, server capped to 1 vCPU / 2 GB, k6 on the same box | 2026-10-14 | saturation, 64 VUs, 90 s | 10,482 | 13.6 | 19.93 | 52.5 mean / 84.2 max | 341 | 0 |
| Rig: Hetzner CPX11 x86_64, 2 vCPU, server capped to 1 vCPU / 2 GB, k6 on the same box | 2026-10-14 | ladder, 2,500 users (k6 `load.js`, ramp 60 s + 2 min) | 207.8 | 0.52 | 0.86 | 8.6 mean / 91.6 max | 336 | 0 |
| Rig: Hetzner CPX11 x86_64, 2 vCPU, server capped to 1 vCPU / 2 GB, k6 on the same box | 2026-10-14 | idle, 28,000 target, better of 2 tries: 9,887 held | n/a | n/a | 409 | n/a | 330 | 14,856 |

Hetzner rehearsal (2026-10-14, `ash`, Ubuntu 24.04.4): the k6 load generator and the idle client ran on the same 2 vCPU box as the
server (server capped to one vCPU), so these rows are far below the Docker rig and not comparable to it. On x86 all 42 checks
passed (install 5.3 s, build 60 s, health in 0.38 s). The 5,000-user ladder step was not completed: the kernel OOM-killed k6
(1.36 GB on a 1.9 GB box); the server was unharmed. The idle run held 9.9k of the 28k target connections (14,856 client errors,
reproduced on a second try); the cause is not proven. Likely the client's 5 s response timeout while client and server were both
CPU-bound, but the server never reached its connection cap (65,023) and the kernel drop counters stayed 0. Full detail is in
MEASUREMENTS.md, sections `rehearsal-x86` and `final-build`.

The ladder is think-time bound (about 0.08 req/s per user), so its req/s measures the load script, not the server; the server
used 14 % of its core. Saturation (no think time) is the throughput measure. Durability: 200 acknowledged creates, `kill -9`,
restart, 0 lost.

## How to run locally

```bash
sudo bash install.sh                      # once, Ubuntu 24.04: gcc, curl, unzip, Temurin 25 in /opt/jdk-25
bash build.sh                             # SQLite 3.54.0 from source (PGO on Linux/gcc) + javac --release 25 into bin/
SQLITE_PATH=/path/feed.db JWT_SECRET=... HOST=0.0.0.0 PORT=80 bash start.sh   # foreground
bash tests/run.sh                         # unit and loop tests, prints ALL TESTS PASSED
bash test/run.sh submissions/java-nio-atlasjedi   # from the repo root: the challenge's 42 checks
```

Environment: `SQLITE_PATH` (default `seed/feed.db`), `JWT_SECRET` (default `twelve-dollar-challenge`), `HOST` (default
`127.0.0.1`), `PORT` (default `3000`); `start.sh` also accepts an optional `JVM_FLAGS` override (default
`-XX:+UseSerialGC -Xms256m -Xmx512m -XX:+AlwaysPreTouch -Xss256k -XX:MaxDirectMemorySize=256m`).

`build.sh` runs a profile-guided SQLite build on Linux with gcc: it compiles an instrumented library, runs `app.Train` (reads,
creates and likes on a throwaway copy of the seed) and recompiles with the profile (about 27 s on one CPU, against about 10 s
plain). Any failure falls back to the plain `-O2` build and says why (`PGO: skipped (...)`). `PGO=0 bash build.sh` forces the
plain build; macOS/clang always builds plain.

## Files

- `install.sh` - root, once: build tools and the pinned Temurin 25 tarball.
- `build.sh` - SQLite amalgamation (pinned, hash-checked), optional PGO, `javac`.
- `start.sh` - foreground launcher with the JVM flags.
- `src/app/Main.java` - reads the four env vars, wires DB, router and server.
- `src/app/Server.java` - the NIO event loop: accept, read, write, backpressure, LRU, fd cap, deferred (post-COMMIT) writes.
- `src/app/Http.java` - zero-allocation HTTP/1.1 request framer.
- `src/app/Router.java` - byte-level dispatch, auth for POST routes, error-to-500 mapping.
- `src/app/Handlers.java` - the five endpoints.
- `src/app/Jwt.java` - HS256 verifier (hand-rolled base64url and claim scan).
- `src/app/BodyJson.java` - strict parser for `{"body": "..."}`.
- `src/app/Resp.java` - response writer into the shared direct buffer.
- `src/app/Db.java` - the connection, pragmas, prepared statements, group commit, checkpoint policy.
- `src/app/Sqlite.java` - Panama FFM bindings to libsqlite3.
- `src/app/Os.java` - `RLIMIT_NOFILE` via FFM.
- `src/app/Train.java` - PGO training workload, run only by `build.sh`.
- `tests/run.sh`, `tests/app/*Test.java` - dependency-free `main`-class tests.
- `.gitignore` - excludes `bin/` and `build/`.
