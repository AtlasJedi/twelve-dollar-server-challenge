# 06 Measurements

Rig: Docker Desktop VM on Apple M5 (10 CPUs / 8 GB), server container `twelve` with `--cpus 1 --memory 2g`
(Ubuntu 24.04 arm64, Temurin 25.0.4.1+1, SQLite 3.54.0), clients are k6 1.x in `grafana/k6` containers and
`IdleClient` in `eclipse-temurin:25-jdk` containers on bridge `twelve-net` (one IP per client container).
Date 2026-10-14. Numbers are relative (an M5 core is several times faster than a shared DO vCPU; clients share the VM).
Server build: branch `feature/java-nio` at cp-13. `up.sh` re-proved `passed=42 failed=0` in Docker; a cold server
restart to first `/health` 200 took 1.3 s.

## baseline-ladder

k6 `load.js`, RAMP_UP=60s, DURATION=2m per step, 20 s idle between steps. CPU % is the container's `docker stats`
value (100 % = the one allotted core), mean and max over the step; RSS and fds are the server peak.

| users | k6 containers | req/s | p50 ms | p95 ms | p99 ms | failed % | CPU % mean / max | RSS MB | fds | k6 RSS per container |
|---|---|---|---|---|---|---|---|---|---|---|
| 2,500 | 1 | 208 | 0.35 | 0.69 | 1.10 | 0 | 4.0 / 6.2 | 332 | 2,513 | 0.8 GiB |
| 5,000 | 1 | 415 | 0.31 | 0.61 | 0.95 | 0 | 6.4 / 12.6 | 352 | 5,013 | 1.0 GiB |
| 10,000 | 2 | 831 | 0.29 | 0.78 | 2.91 | 0 | 10.3 / 17.8 | 350 | 10,013 | 0.9 GiB each |
| 20,000 | 2 | 1,661 | 0.24 | 0.98 | 14.13 | 0 | 13.8 / 22.1 | 363 | 20,013 | 3.1 GiB each |

No k6 container was OOM-killed (all exit code 0), no step reached p99 >= 1,000 ms or failed >= 1 %, so the ladder
ran to the top. The 20,000-user step used about 6.2 GiB of the 7.65 GiB VM for the two k6 containers, so the client
side, not the server, is what stops a 40k step on this rig.

Raw: `tools/load/results/2026-10-14-baseline/ladder-<users>.json` (merged), `ladder-<users>-<i>.json` (k6 summaries),
`ladder-<users>-stats.csv` (server RSS / fds / CPU every 5 s), `ladder-<users>-dockerstats.txt` (container memory
every 10 s). Date 2026-10-14.

Decision: the server is nowhere near its ceiling on this rig. At 20,000 users p99 is 14 ms, CPU averages 14 % of
its core, RSS 363 MB; the load generator (k6 memory, think-time-limited ~1,660 req/s) saturates first. Nothing must
change before the A/Bs; use VUS=10000 CONTAINERS=2 as the A/B point (p99 2.9 ms, a differences-visible but
non-saturated regime) and treat req/s as fixed by the k6 script, so compare p99 and CPU %.

## baseline-idle-60k

`IdleClient` x3 containers, 20,000 connections each, each sends `GET /feed` every 10 s per connection, HOLD=300 s,
server freshly restarted. Container `nofile=65535`, so the server cap `Server.defaultMaxConns()` is 65,023.

| run | connections | requests | errors | p50 ms | p99 ms | RSS MB | fds | CPU % (mean while held) |
|---|---|---|---|---|---|---|---|---|
| staggered (STAGGER=12 s between client starts) | 60,000 | 1,832,725 | 0 | 0.33 | 63.9 | 321.5 | 60,013 | 43.3 |
| burst (all three clients start together, 6,000 connects/s) | 57,651 | 1,777,212 | 2,349 | 0.33 | 494 | 323.6 | 57,664 | 40.5 |

The first (burst) attempt opened 60,000 sockets but lost 2,349 (3.9 %): during the roughly 10 s connect burst the
single-core server stalled some first requests past the client's 5 s response timeout (a repeat with a 40 s hold
showed the same: 646 errors, p99 4.7 s), and the client closed those. Not the limiting factors: client ephemeral ports
(20,000 per IP of 28,231), the server fd cap (65,023), conntrack (limit 262,144; no listen overflows or
drops), memory (RSS 324 MB of 2 GB). Fix applied in the rig only: `idle.sh` gained `STAGGER` (seconds between client
container starts) so the aggregate connect rate is 2,000/s; no kernel or server change. With it all 60,000 are held
for the 5 min with zero errors. Idle cost is about 5 KB RSS per connection above the 300 MB base, fds track
connections one to one.

Raw: `tools/load/results/2026-10-14-baseline/idle-60000.json` (staggered, merged), `idle-60000-{1,2,3}.json`,
`idle-60000-stats.csv`; the burst attempt is `burst-idle-60000*.json`, the 40 s repeat is in
`tools/load/results/2026-10-14-diag/`. Date 2026-10-14.

Decision: the 60k idle claim holds (60,000 held, 0 errors, p99 64 ms, RSS 322 MB, req/s about 6,100 from the
idle poll). Nothing must change in the server before the A/Bs. Open point for the submission: a sudden burst of
about 6,000 new connections/s into one core can stall first requests for seconds; the real test ramps, so no action,
but do not report the burst run as a pass.

## durability

`tools/load/durability.sh`: 200 `POST /posts` with a seed token inside the container (all 201), then `kill -9` of the
java process, restart on the same `/tmp/feed.db` (WAL, synchronous=NORMAL), count rows again.

| posts before | acknowledged creates | posts after | lost |
|---|---|---|---|
| 500,000 | 200 | 500,200 | 0 |

Output: `durability verification passed`. Log `tools/load/results/2026-10-14-baseline/durability.log`; p99 of the
`POST /posts` path under load is in the ladder tables (req/s is the k6 total). Date 2026-10-14.

Decision: rule 6 holds on this build; no change, repeat the check once after each A/B that touches commit behavior
(group commit especially).

## baseline-sat

Saturation harness `tools/load/sat.js` + `sat.sh`: k6 `constant-vus` 64, 90 s, no sleep; per iteration GET /feed, GET /posts/:id
(id from the feed), POST like with p=0.15, POST /posts with p=0.02; two runs 20 s apart. CPU % is the container's
`docker stats` mean over the run (100 % = the one allotted core). Build: cp-13 (autocommit, one COMMIT per write).
Rig: Docker VM M5 / 1 CPU 2 GB container, k6 in a sibling container on `twelve-net`. Date 2026-10-14.

| run | req/s | p99 ms | failed % | CPU % mean |
|---|---|---|---|---|
| 1 | 70,349 | 2.72 | 0 | 90.4 |
| 2 | 64,979 | 3.07 | 0 | 87.9 |

Raw: `tools/load/results/2026-10-14-sat/sat-baseline.json` (merged), `sat-baseline-<n>.json` (k6 summaries),
`sat-baseline-<n>-stats.csv`.

Decision: this is the reference for every A/B (best run 70,349 req/s, p99 2.72 ms). Run-to-run spread is about 8 %, so a
3 % gain is only credible when both runs of the variant beat both baseline runs or the gain is well above the spread.

## ab-group-commit

Variant: one `BEGIN`/`COMMIT` per loop tick (writes of the whole select batch share it); a 201/200 for a write is held
per connection and written only after `COMMIT` returned; a failed commit rolls back and drops the held connections with an
RST. Same harness, rig and date as `baseline-sat` (2026-10-14, Docker VM M5 / 1 CPU 2 GB container). The baseline server had
served about 36 min of earlier load on its DB; the variant started from a fresh `feed.db`.

| variant | run | req/s | p99 ms | failed % | CPU % mean | p99 ms GET /feed | GET /posts/:id | POST like | POST /posts |
|---|---|---|---|---|---|---|---|---|---|
| baseline cp-13 | 1 | 70,349 | 2.72 | 0 | 90.4 | 2.75 | 2.70 | 2.71 | 2.72 |
| baseline cp-13 | 2 | 64,979 | 3.07 | 0 | 87.9 | 3.10 | 3.04 | 3.01 | 3.12 |
| group commit | 1 | 78,602 | 2.38 | 0 | 88.5 | 2.45 | 2.26 | 2.65 | 2.80 |
| group commit | 2 | 77,274 | 2.33 | 0 | 89.9 | 2.38 | 2.21 | 2.58 | 2.79 |

Best-of-two: 78,602 vs 70,349 req/s (+11.7 %), p99 2.33 vs 2.72 ms (better). Both variant runs beat both baseline runs.
CPU stays near the single core in both (the server is saturated, so the gain shows as throughput). 42/42 green,
`bash tests/run.sh` green including `GroupCommitTest` (no bytes before COMMIT, 100 pipelined creates in one tick, failed
commit closes silently and the loop survives), kill -9 durability test unchanged.

Raw: `tools/load/results/2026-10-14-sat/sat-group-commit.json`, `sat-group-commit-<n>.json`, `-stats.csv`.

Decision: keep (+11.7 % req/s at better p99 meets D11's >= 3 %).

Equal-footing re-run (Task 16 step 0, 2026-10-14, same rig): both builds started from a fresh `feed.db` (cp-13 built from a
detached worktree at 0706c63, cp-15 from the java-nio worktree), container recreated for each, same sat harness.

| variant | run | req/s | p99 ms | failed % | CPU % mean |
|---|---|---|---|---|---|
| cp-13 fresh DB | 1 | 70,500 | 2.44 | 0 | 88.3 |
| cp-13 fresh DB | 2 | 69,059 | 2.44 | 0 | 91.0 |
| cp-15 fresh DB | 1 | 76,771 | 2.50 | 0 | 91.9 |
| cp-15 fresh DB | 2 | 78,794 | 2.26 | 0 | 88.8 |

Best-of-two 78,794 vs 70,500 req/s (+11.8 %), p99 2.26 vs 2.44 ms (better); both cp-15 runs beat both cp-13 runs.
Raw: `tools/load/results/2026-10-14-gc/sat-cp13-fresh*.json`, `sat-cp15-fresh*.json`.

Decision: keep still holds on equal footing (+11.8 % req/s at better best-run p99, matching the earlier +11.7 %); the stale-DB
concern did not change the result.

## ab-gc

Three JVM flag sets on the cp-15 build (group commit kept), selected through the new `JVM_FLAGS` override in `start.sh`
(unset = today's string, byte-identical). Each set: fresh `feed.db`, `sat.sh` RUNS=2 (64 VUs, 90 s), then one more
90 s run with a JFR recording (`settings=profile`, `tools/load/jfr.sh`; the JFR run's own req/s is not used for ranking).
Rig: Docker VM M5 / 1 CPU 2 GB container, k6 in a sibling container. Date 2026-10-14.

- (a) `-XX:+UseSerialGC -Xms256m -Xmx512m -XX:+AlwaysPreTouch -Xss256k -XX:MaxDirectMemorySize=256m` (current default)
- (b) `-XX:+UseParallelGC -Xms256m -Xmx512m -XX:+AlwaysPreTouch -Xss256k -XX:MaxDirectMemorySize=256m`
- (c) `-XX:+UseZGC -Xmx512m -Xss256k -XX:MaxDirectMemorySize=256m`

| set | req/s run 1 | run 2 | p99 ms run 1 / 2 | CPU % mean | RSS MB peak | GC pauses (count / max ms / p99 ms) | alloc MB per 10k req |
|---|---|---|---|---|---|---|---|
| (a) Serial | 75,942 | 76,457 | 2.55 / 2.63 | 89.7 / 91.4 | 409 | 203 / 5.12 / 4.04 | 20.1 |
| (b) Parallel | 77,025 | 73,816 | 2.50 / 2.58 | 88.8 / 88.8 | 409 | 165 / 4.14 / 4.04 | 20.1 |
| (c) ZGC | 74,102 | 71,838 | 2.40 / 2.49 | 92.6 / 89.5 | 655 | 168 phases / 0.048 / 0.044 | 24.6 |

Pause counts are `jdk.GCPhasePause` events in the 90 s JFR run (Serial/Parallel: young "GC Pause"; ZGC: its three STW
phases, so 168 phases is about 56 cycles). RSS is the peak of the java process over the two sat runs. Allocation is the
summed `jdk.ObjectAllocationSample` weight (equals the sum of `jdk.ThreadAllocationStatistics`) over the requests the JFR
run served.

60k idle (`CONNS=60000 CONTAINERS=3 HOLD=120 STAGGER=12`, fresh server per set; CPU is the mean while >= 95 % of the
peak fds are open, so it includes the tail of the staggered ramp):

| set | connections | errors | p99 ms | RSS MB | CPU % |
|---|---|---|---|---|---|
| (a) Serial | 60,000 | 0 | 67.0 | 328 | 44.3 |
| (b) Parallel | 60,000 | 0 | 101.9 | 345 | 44.1 |
| (c) ZGC | 60,000 | 0 | 7.2 | 474 | 46.0 |

Top 10 allocation sites of the default set (a), JFR run `a-jfr` (13,853 MB sampled over 6.89 M requests):

| class | site (first `app.*` frame, via) | MB | % |
|---|---|---|---|
| `GlobalSession$HeapSession` | `MemorySegment.ofBuffer(ByteBuffer)`: `Sqlite$Stmt.copyText:252` and `Resp.java:186` (no app frame in the sampled stack) | 6,099 | 44.0 |
| `NativeMemorySegmentImpl` | `Sqlite$Stmt.colText:240` (`p.reinterpret(n + 1)`) | 5,131 | 37.0 |
| `NativeMemorySegmentImpl` | `Sqlite$Stmt.copyText:252` (`p.reinterpret(n)`) | 2,498 | 18.0 |
| `DirectByteBuffer` | `Server.hold:147` (`allocateDirect` per held write response) | 32.7 | 0.2 |
| `byte[]` | `MessageDigest.digest` (JWT HMAC/SHA) | 21.9 | 0.2 |
| `int[]` | JDK classfile `EntryMap.<init>` (startup/lambda spinning) | 19.3 | 0.1 |
| `Cleaner` | `Server.hold:147` | 17.8 | 0.1 |
| `DirectByteBuffer$Deallocator` | `Server.hold:147` | 10.9 | 0.1 |
| `PoolEntry[]` | JDK `SplitConstantPool` (startup) | 2.8 | 0 |
| `NativeMemorySegmentImpl` | other `makeNativeSegmentUnchecked:83` | 2.7 | 0 |

Raw: `tools/load/results/2026-10-14-gc/` - `sat-{a,b,c}-sat*.json|-stats.csv`, JFR recordings `{a,b,c}-jfr.jfr` with
parsed `{a,b,c}-jfr-jfr.json`, idle `{a,b,c}-idle-60000.json|-stats.csv`. (`a-serial-jfr*` is a first trial run of the
same set.)

Per-request allocation on the warm path is NOT zero: about 2.0 KB (20 MB per 10k requests). 99.8 % of it is FFM views,
not heap data. Input for Task 17 (JSON output path): (1) `Sqlite.Stmt.colText` allocates a `MemorySegment`
(`reinterpret`) per text column read; (2) `Sqlite.Stmt.copyText` allocates a `reinterpret` segment plus a
`MemorySegment.ofBuffer(dst)` view and its `HeapSession` per copied column (the largest site, 44 %); (3) `Resp.str(
MemorySegment)` / `Resp.java:186` repeats the `ofBuffer` view per string; (4) `Server.hold` allocates a direct buffer
(plus `Cleaner`/`Deallocator`) per held write response, small (0.5 %) but also a direct-memory churn source. Young
collections are cheap anyway (203 pauses, 147 ms total in 90 s, max 5 ms), so the cost of this garbage is allocation
and cache pressure, not GC pauses.

Decision: keep (a) as the `start.sh` default. By D11, (b) is +0.7 % req/s on the best run (77,025 vs 76,457; its second run
is lower, 73,816), well under 3 %; (c) is -3.1 % best-of-two (74,102) with a better p99 (2.40 vs 2.55 ms) but 246 MB more
RSS; idle CPU differs by at most 1.7 points (< 3), so no set meets either threshold. `start.sh` is unchanged apart from
the `JVM_FLAGS` override. ZGC's idle p99 (7 ms vs 67 ms) and sub-0.1 ms pauses are noted for the README but do not meet the
keep rule.

## ab-json

JSON output path A/B on the cp-16 build. Rig: Docker VM M5 / 1 CPU 2 GB container, k6 in a sibling container, `sat.sh`
RUNS=2 (64 VUs, 90 s, fresh `feed.db` per build), date 2026-10-14. Each build re-proved `passed=42 failed=0` in Docker.

- cp-16 reference: ab-gc set (a), the same code as cp-15 (FFM `reinterpret` + `MemorySegment.ofBuffer` per column).
- zero-alloc (A): one global `Sqlite.ALL` view of the address space, `colTextAddr` (native `JAVA_LONG` return, no segment),
  `Resp.BODY_SEG` created once, `Resp.str(ByteBuffer, long addr, int len)`, `copyText(c, dst, dstSeg)`; `Server.hold` uses heap
  buffers. 2 downcalls per text column, 1 per integer.
- sql-json (B): `json_group_array(json_object(...))` / `json_object(...)` over the same query with aliases, one `copyText`
  between the `{"posts":` / `{"post":` wrappers. Bytes: `test/golden/feed.json` and `post-{1,250000,500000}.json` are
  byte-identical (the seed has no control characters; SQLite escapes `"` `\` and short forms, not `/`).

| build | req/s run 1 | run 2 | p99 ms run 1 / 2 | CPU % mean | alloc MB per 10k req (JFR sample / thread stats) |
|---|---|---|---|---|---|
| cp-16 reference (ab-gc a) | 75,942 | 76,457 | 2.55 / 2.63 | 89.7 / 91.4 | 20.1 / 20.1 |
| zero-alloc (A) | 76,938 | 79,267 | 2.67 / 2.34 | 91.4 / 89.0 | 0.18 / 0.25 |
| sql-json (B) | 52,903 | 41,721 | 3.14 / 3.85 | 89.1 / 92.2 | not measured (rejected on speed) |

Allocation per 10k requests, JFR run `zero-jfr` (7,189,716 requests, 90 s, `settings=profile`): 0.18 MB sampled
(0.25 MB by `ThreadAllocationStatistics`), down from 20.1 MB (about 2.0 KB per request to about 18 bytes), a 99 % cut.
Remaining top sites: `Server.run:95` object (51 %, per-connection setup), `Server.hold:147` heap `byte[]` for held write
responses (29 %, 38 MB), JDK `EntryMap` startup ints (16 %). GC pauses in the 90 s run: 2 (max 8.6 ms), versus 203 before.
`JsonAbTest` proves 0 bytes allocated over 20,000 warm `Db.feed` calls (64 KB limit).

Raw: `tools/load/results/2026-10-14-json/` - `sat-zero-alloc*.json|-stats.csv`, `sat-sql-json*.json|-stats.csv`,
`zero-jfr.jfr` with parsed `zero-jfr-jfr.json`.

Decision: keep zero-alloc. Best run +3.7 % req/s over the cp-16 reference (79,267 vs 76,457), both runs above both reference
runs, p99 equal or better on the best run (2.34 vs 2.55 ms), allocation cut 99 %, and it is kept regardless as not slower.
Reject sql-json: byte-identical on the golden files but 31 % slower on its best run (52,903 vs 79,267 req/s) with worse
p99 (3.14 ms); SQLite's JSON text building costs more than the Java writer. Its code is deleted (no flag).

## ab-create

Create path A/B in `tests/app/CreateAbTest.java` (variant code now deleted). A = `INSERT ... RETURNING id, created_at`
(two steps to DONE); B = `INSERT INTO posts (user_id, body) VALUES (?, ?)` stepped to DONE, then
`SELECT created_at FROM posts WHERE id = ?` with `lastInsertRowid()`, inside the same tick transaction. Microbench rig:
this Mac natively (Apple M5, JDK 27 `--release 25`, libsqlite3.dylib from build.sh), fresh seed copy per variant, 2,000
warm-up then 10,000 timed creates, `commitTick()` every 100 creates, median of per-call `nanoTime` deltas. Both variants
returned strictly increasing ids and `created_at` of the form `YYYY-MM-DDTHH:MM:SS.mmmZ`. Date 2026-10-14.

| run | A RETURNING median ns | B INSERT + read median ns | B vs A |
|---|---|---|---|
| 1 | 8,459 | 1,084 | -87.2 % |
| 2 | 8,292 | 1,125 | -86.4 % |
| 3 | 8,375 | 1,084 | -87.1 % |
| median of medians | 8,375 | 1,084 | -87.1 % |

Saturation confirmation on the Docker rig (M5 VM, 1 CPU 2 GB container, k6 sibling, `sat.sh` 64 VUs 90 s) for the build
with B plus the `Server.keyConsumer` allocation fix (cached `Consumer`, no per-iteration lambda):

| set | req/s run 1 | run 2 | p99 ms run 1 / 2 | CPU % mean |
|---|---|---|---|---|
| insert-read (first set) | 2,244 | 49,501 | 5.22 / 3.55 | 94.3 / 88.7 |
| insert-read-b (repeat) | 73,284 | 77,952 | 2.62 / 2.38 | 89.6 / 90.5 |

The first set ran while the host was loaded (load average about 9 from other sessions), run 1 collapsed to 2,244 req/s;
the repeat on a calmer host is in line with the cp-16 / zero-alloc reference (75,942 - 79,267 req/s, p99 2.3 - 2.7 ms).
Creates are 2 % of the mix, so the end-to-end gain is below the saturation noise; no regression.

Raw: `tools/load/results/2026-10-14_1629/sat-insert-read*.json`, `tools/load/results/2026-10-14_1651/sat-insert-read-b*.json`.

Decision: keep B (INSERT + SELECT by rowid). The microbench median drops 87 % (8,375 to 1,084 ns, far above the 3 % D11
bar), consistent with RETURNING materialising its rows in an ephemeral table. `Db.create` is now B; the RETURNING
statement is deleted, and the test keeps the format check and a median < 200 us assertion. Saturation: no regression
(best 77,952 req/s, p99 2.38 ms vs reference 79,267 / 2.34).

## ab-pgo

PGO-compiled SQLite (Linux/gcc only). `build.sh` compiles the amalgamation with `-O2 -fprofile-generate -fprofile-dir=build/pgo`,
runs `app.Train` (20,000 feeds + 20,000 post reads, 2,000 creates + 2,000 likes on a throwaway copy of `seed/feed.db`,
`commitTick()` every 50 writes), recompiles with `-O2 -fprofile-use -fprofile-correction -Wno-missing-profile`. Any failure
falls back to the plain `-O2` build (`PGO: skipped (<reason>)`); `PGO=0` forces the plain build; macOS/clang prints
`PGO: skipped (clang)`. Rig: Docker VM M5 / 1 CPU 2 GB container (arm64, Ubuntu 24.04, gcc), k6 sibling, `sat.sh` 64 VUs 90 s,
two runs 20 s apart, back-to-back (PGO set first). Date 2026-10-14.

Build wall time of `build.sh` (container, 1 CPU, run as `runner`, sqlite zip already cached, two measurements each):

| build | seconds |
|---|---|
| plain `-O2` (`PGO=0`) | 10.4 / 10.7 |
| PGO | 26.7 / 26.6 |

amd64 emulated container: not measured (qemu build of the instrumented library plus the 250 MB training copy was not attempted
within the 10-minute allowance; the Hetzner rehearsal in Task 21 builds natively on x86).

| set | run | req/s | p99 ms | failed % | CPU % mean |
|---|---|---|---|---|---|
| pgo | 1 | 84,571 | 2.26 | 0 | 89.5 |
| pgo | 2 | 84,353 | 2.16 | 0 | 89.1 |
| pgo-off | 1 | 79,135 | 2.28 | 0 | 92.1 |
| pgo-off | 2 | 70,903 | 3.22 | 0 | 89.9 |

Caveat: the host load average was 5 to 11 during these runs (other sessions plus the k6 container; 2.6 when idle before),
above the protocol's 4, and the pgo-off run 2 is visibly depressed by it. Comparing best runs: +6.9 % req/s (84,571 vs
79,135) at equal or better p99 (2.16 vs 2.28 ms); both PGO runs beat both plain runs. Earlier plain references in this document
(ab-create 73,284 / 77,952; cp-16 up to 79,267) are all below the PGO runs, which supports a real gain of about 5 to 8 %.

Raw: `tools/load/results/2026-10-14_ab-pgo/sat-pgo*.json`, `sat-pgo-off*.json` (and `-stats.csv`); up.sh log with
`PGO: on` and `passed=42 failed=0` in the scratchpad `pgo-up.log`.

Decision: keep PGO (default on, `PGO=0` knob documented in `build.sh`): +6.9 % req/s on the best run at better p99 meets D11's
>= 3 %, and the PGO build is 26.7 s on the arm64 1-CPU container, far under the 5-minute ceiling. CI seed timing: the workflow
runs `seed/make-seed.sh` before `install.sh` and `build.sh`, and `test/run.sh` refuses to build without `seed/feed.db`, so the seed
exists when `build.sh` runs; `Train` still falls back to a generated 300-user / 500-post schema-compatible DB when the seed
path is missing, so training never depends on it.

## ab-virtual-threads

Virtual-thread variant (element 14b, D12): `VtServer` = blocking `ServerSocketChannel.accept()` on the main thread, one virtual
thread per connection, per-connection heap `byte[]` read buffer (8 KB, growing to the parser limit), blocking
`SocketChannel.read`, `Http.parse` framing with pipelining, and per request `synchronized (lock) { router.handle; copy response;
tick(); afterTick() }` with the blocking write outside the lock and released only if the commit succeeded (rule 6). Same
`Router`/`Db`/`Jwt`; the Router needed no change (a `Server.Conn` with null channel and key; the Router only sets `c.deferred`).
Tests: `VtTest` (RouterTest cases, malformed, pipelined pair, 200 clients x 20 keep-alive requests) green, 42/42 green on both
`app.Main` and the variant. Rig: Docker VM M5 / 1 CPU 2 GB container, `sat.sh` 64 VUs 90 s two runs 20 s apart, `idle.sh`
`CONNS=60000 CONTAINERS=3 HOLD=120 STAGGER=12`, same session, JVM flags unchanged (`-Xmx512m`, `MaxDirectMemorySize=256m`).
Date 2026-10-14. The variant ran first, then `app.Main` as the same-session reference.

| variant | run | req/s | p99 ms | failed % | CPU % mean |
|---|---|---|---|---|---|
| virtual threads | 1 | 70,498 | 2.30 | 0 | 88.9 |
| virtual threads | 2 | 69,524 | 2.26 | 0 | 87.2 |
| NIO `Server` (nio-ref) | 1 | 86,490 | 2.05 | 0 | 89.9 |
| NIO `Server` (nio-ref) | 2 | 86,041 | 2.12 | 0 | 89.5 |

| variant | connections held (end) | opened | errors | p99 ms | RSS MB peak | CPU % |
|---|---|---|---|---|---|---|
| virtual threads | 0 (peak about 30,500 fds) | 37,207 | 37,207 | 84.6 | 601 | 98.5 (GC thrash) |
| NIO `Server` (same session) | 60,000 | 60,000 | 0 | 11.7 | 326 | 42.1 |

Why the virtual-thread run cannot hold 60k: a diagnostic 10 s run (two server runs) showed `java.lang.OutOfMemoryError: Java heap
space` in the accept loop at about 30,000 connections. Every parked connection owns an 8 KB heap read buffer plus the JDK's
temporary direct buffer for the blocking read and a virtual-thread stack chunk; with `-Xmx512m` the heap is exhausted around
30k connections, the Serial collector spins at about 98 % CPU, and the remaining connections are reset. The NIO loop holds 60,000
with one shared 64 KB read buffer and about 600 B per connection.

Raw: `tools/load/results/2026-10-14_ab-vt/sat-vt*.json`, `sat-nio-ref*.json`, `idle-60000-vt.json`, `idle-60000-nio.json`
(+ `-stats.csv`), `idle-60000-vt-diag-10s.json`. The per-container idle files `idle-60000-<i>.json` are the NIO run (overwritten).

Decision: delete the virtual-thread variant. It is 18.5 % slower on the best run (70,498 vs 86,490 req/s) at a worse p99
(2.26 vs 2.05 ms) and cannot hold 60k connections in the 512 MB heap (about 30k, then OOM), so none of D11/D12's conditions
(>= 3 % faster, 60k held, CPU/RSS not worse) is met. `VtServer`, `VtMain`, `VtTest` and the `MAIN_CLASS` knob (start.sh, tools/load)
are removed; the `java-nio: listening on HOST:PORT, maxConns=N, pid=P` startup line in `Main` stays. The container runs `app.Main`.

## rehearsal-x86

Rig: Hetzner CPX11 (AMD EPYC-Rome x86_64, 2 vCPU limited to CPUQuota=100%, MemoryMax=2G), 2026-10-14. Location `ash` (Ashburn; CPX11 is
withdrawn from the EU locations), Ubuntu 24.04.4, kernel 6.8.0-138, 1.9 GB RAM, no swap. Temurin 25.0.4.1+1, SQLite 3.54.0, k6 v2.3.0 on the
same box. k6 shares the box's second vCPU with the server (server capped to one vCPU), so these are rehearsal numbers, not comparable to the
Docker rig. Server fresh (new `feed.db` copy, no -wal/-shm) per step, started as `runner` on 0.0.0.0:80 under a unique transient unit
`twelve-<step>` (CPUQuota=100%, MemoryMax=2G, LimitNOFILE=65535); `/health` 200 and the `java-nio: listening` line verified before every step.

### Install, build, tests

| step | wall time |
|---|---|
| `bash install.sh` (apt + Temurin download, sha256 checked) | 5.3 s |
| `build.sh` clean, PGO on (`bin`/`build` removed first) | 60.0 s (ceiling 5 min) |
| `start.sh` to first `/health` 200 | 0.38 s (ceiling 60 s) |

`sudo -u runner bash test/run.sh submissions/java-nio-atlasjedi` (build + start + test, 1 m 0.5 s): `passed=42 failed=0`.

### Ladder (`load.js`, RAMP_UP=60s, DURATION=2m, instrumented copy logging NON200)

| users | req/s | p95 ms | p99 ms | failed % | CPU % mean / max | RSS MB peak |
|---|---|---|---|---|---|---|
| 2,500 | 207.8 | 0.52 | 0.86 | 0 (45,711 of 45,711 checks passed) | 8.6 / 91.6 | 336 |
| 5,000 | not completed | n/a | n/a | n/a | n/a | 341 |

The 5,000-user step did not finish: the kernel OOM-killed **k6** (anon-rss 1.36 GB, `dmesg-oom.txt`, `rc=137`) at 1 m 29 s with 5,000 VUs,
while `free` showed 51 MB available. The server was unharmed (RSS 341 MB, no error in its log); the load generator does not fit next to the
server in 1.9 GB, which is a property of the rig, not of the submission. CPU columns come from `ps -o %cpu`, which is the process lifetime
average (includes JVM start), so the mean is understated; the max is the startup-to-date value at the busiest sample.

### Saturation (`sat.js`, 64 VUs, 90 s, instrumented copy)

| req/s | p99 ms | failed % | CPU % mean / max | RSS MB peak |
|---|---|---|---|---|
| 10,482 | 19.93 | 0 (878,323 of 878,323 checks passed; 0 NON200 lines) | 52.5 / 84.2 | 341 |

p95 13.6 ms, max 130.5 ms, 943,473 requests. k6 and the server compete for the 2 vCPUs, so the rate is far below the 87k of the Docker rig.

### Idle (`IdleClient 127.0.0.1 80 28000 10 120`, `ulimit -n 65535`, TIME_WAIT drained to 65 first) - ANOMALY

| try | connections held | opened | requests | errors | p50 ms | p99 ms | RSS MB | exit |
|---|---|---|---|---|---|---|---|---|
| 1 | 9,618 | 24,441 | 137,787 | 14,823 | 0.18 | 373.6 | 326 | 2 |
| 2 (TIME_WAIT 0, `nstat`/`ss` sampled) | 9,887 | 24,743 | 140,995 | 14,856 | 0.18 | 409.0 | 330 | 2 |

Target 28,000 not reached; reproduced on the second try, so it is not leftover TIME_WAIT (earlier first-attempt guess disproved: ss showed
65 and 0 TIME_WAIT before the runs). Evidence: the server held about 21,000 fds at the 10 s mark (so about 21k connections were accepted), then
dropped to 9,900 and stayed there for the whole hold, while the client counts the missing ones as `errors`. Kernel counters: ListenOverflows 0,
ListenDrops 0, TCPReqQFullDrop 0, TCPBacklogDrop 0, TCPAbortOnMemory 0, so no accept-queue or memory drops. Server `maxConns=65023`, its own
LRU eviction (cap 65,023) was not reached, server limit `Max open files 65535`, server CPU 90% at ramp then ~10%. The server log has no error
line. Unexplained: the client closes a connection on a 5 s response timeout or an IOException (`IdleClient.java` `fail`), and with the client
JVM (single selector thread) and the server both CPU-bound during the 2,000 conns/s ramp on two shared vCPUs, a client-side response timeout is
the likeliest cause, but it was not proven (the client does not split timeouts from resets). Not guessed further; follow-up if wanted: run
IdleClient with a slower ramp (STAGGER), or split the error counter by cause.

### NON200 explanation

Ladder 2,500: one NON200 line, `like 200/201 status=0 err=context canceled` at the end of the run (k6 interrupted an in-flight request at
shutdown, not a server error). Saturation: 0 NON200 lines. Ladder 5,000: none before the k6 OOM kill. The 12% `feed 200` failures of the
first attempt did not reproduce with clean unit restarts and TIME_WAIT <= 5,000 before every step (it was 2,501 before the 5,000 step and
4,985 before sat), consistent with the stale-server and port-exhaustion explanation.

Output lines: IdleClient try 1 `{"connections":9618,"opened":24441,"requests":137787,"errors":14823,"p50_ms":0.182,"p99_ms":373.637}`; k6 (sat) `http_reqs......: 943473 10481.587/s`, `http_req_failed: 0.00%`.

Raw: `tools/rehearsal/results/` - `install.txt`, `step23.out` (build, 42/42, time to health), `ladder-2500.json|.out`, `ladder-5000.out`,
`sat.json|.out`, `idle.out`, `idle2.out`, `idle2-diag.txt`, `stats-*.txt`, `net-*.txt` (ss/sysctl/TIME_WAIT), `twelve-*.log`, `journal.txt`,
`dmesg-oom.txt`, `load-nz.js`, `sat-nz.js`, `run2.sh`, `idle2.sh`, `steps.txt`; first attempt in `run1-first-attempt/`.

Decision: the submission behaves on x86 Ubuntu 24.04 as on the Docker rig for correctness and cold start: install 5 s, build 60 s, health in
0.38 s, `passed=42 failed=0`, 2,500 users and 64-VU saturation with 0 failures and sub-20 ms p99. Two things did not complete: the 5,000-user
step (k6 OOM, a rig limit) and 28,000 idle connections (about 9.9k held, unexplained, client-side suspected). Ship, because the rules (42/42,
build under 5 min, health under 60 s) pass; the idle shortfall on a single-IP loopback client with shared vCPUs is not evidence against the 60k
result on the Docker rig, but it is open and not proven harmless.

## final-build

Final code, branch `feature/java-nio` at cp-20 (157c71c): group commit, zero-allocation columns, INSERT + read create, PGO
SQLite, checkpoints on the tick, fd cap + LRU, SerialGC 256-512 MB. `up.sh` rebuilt the container from the worktree HEAD and
re-proved `passed=42 failed=0` (`up.log`). Rig: Docker VM M5 / 1 CPU 2 GB container (Ubuntu 24.04 arm64, Temurin 25.0.4.1+1,
SQLite 3.54.0), k6 and `IdleClient` in sibling containers on `twelve-net`. Date 2026-10-14. Numbers are relative (M5 core, shared VM),
not the DigitalOcean droplet. Server restarted fresh (new `feed.db`) before each ladder step and the idle run.

### Saturation (`sat.sh` LABEL=final RUNS=2, 64 VUs, 90 s, no sleep)

| run | req/s | p99 ms | failed % | CPU % mean | RSS MB peak |
|---|---|---|---|---|---|
| 1 | 87,809 | 2.20 | 0 | 89.9 | 379 |
| 2 | 84,136 | 2.39 | 0 | 88.3 | 411 |

Best 87,809 req/s at p99 2.20 ms; baseline-sat (cp-13) was 70,349 / 2.72 ms, so +24.8 % req/s and better p99.

### Ladder (`load.js`, RAMP_UP=60s, DURATION=2m, 2 k6 containers)

| users | req/s | p95 ms | p99 ms | failed % | CPU % mean / max | RSS MB peak | fds |
|---|---|---|---|---|---|---|---|
| 10,000 | 831 | 0.67 | 1.65 | 0 | 9.5 / 15.9 | 346 | 10,013 |
| 20,000 | 1,663 | 0.87 | 4.05 | 0 | 14.0 / 23.3 | 348 | 20,013 |

Baseline (cp-13) for comparison: 10,000 users p99 2.91 ms, 20,000 users p99 14.13 ms, RSS 363 MB. Oracle:
`k6 ladder verification passed`.

### Idle 60,000 (`CONNS=60000 CONTAINERS=3 HOLD=300 STAGGER=12`)

| connections | requests | errors | p50 ms | p99 ms | RSS MB | fds | CPU % (while held) |
|---|---|---|---|---|---|---|---|
| 60,000 | 1,830,158 | 0 | 0.34 | 2.70 | 325 | 60,013 | 45.4 |

Baseline (cp-13) idle p99 was 63.9 ms, RSS 321.5 MB, CPU 43.3 %. Oracle: `idle verification passed`.

### Durability (`durability.sh`)

| posts before | acknowledged creates | posts after | lost |
|---|---|---|---|
| 500,000 | 200 | 500,200 | 0 |

Output: `durability verification passed`.

Raw: `tools/load/results/2026-10-14-final/` - `sat-final*.json|-stats.csv`, `ladder-{10000,20000}*.json|-stats.csv`,
`idle-60000*.json|-stats.csv`, `durability.log`, `up.log`. Date 2026-10-14.

Decision: the final build is ready to ship. Saturation +24.8 % over the cp-13 baseline at better p99, the 20,000-user ladder p99
falls from 14.1 to 4.1 ms, 60,000 idle connections are held with 0 errors at p99 2.7 ms (was 64 ms), rule 6 holds (0 lost). No
further change before the x86 rehearsal.
