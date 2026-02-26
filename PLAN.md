# pglite4j — Embedded PostgreSQL for Java via WebAssembly

## Vision

A Java library that embeds PostgreSQL (via PGlite compiled to WASI) so that any Java project can add it as a **test dependency** and get a real PostgreSQL instance without spinning up containers or external processes. Standard JDBC drivers (pgjdbc) connect to it transparently.

```xml
<dependency>
  <groupId>com.dylibso.chicory</groupId>
  <artifactId>pglite4j</artifactId>
  <version>...</version>
  <scope>test</scope>
</dependency>
```

Then just use `jdbc:pglite:` as your connection URL — that's it:

```java
// Zero-config: the driver auto-starts an embedded PostgreSQL instance
Connection conn = DriverManager.getConnection("jdbc:pglite:memory://");
conn.createStatement().execute("CREATE TABLE foo (id serial, name text)");

// Spring Boot — just set the URL in application-test.properties:
// spring.datasource.url=jdbc:pglite:memory://
```

No `try`-with-resources, no annotations, no extensions. The custom JDBC driver:
1. Auto-registers via `META-INF/services/java.sql.Driver` (ServiceLoader)
2. On first `connect()`, boots PGlite WASM, opens a local socket, performs the PG handshake
3. Delegates to pgjdbc pointing at `localhost:<auto-port>`
4. Reuses the same PGlite instance for subsequent connections to the same URL
5. Shuts down via JVM shutdown hook

---

## Proof of Concept — What's Already Working

A working test exists on branch `pglite-ai-support` of chicory2:

- **File:** `chicory2/machine-tests/src/test/java/com/dylibso/chicory/testing/MachinesTest.java` (lines 266-634)
- **Test:** `shouldRunPGLite`

### What the test does

1. Extracts `pglite-wasi.tar.xz` (24 MB PostgreSQL 17 compiled to WASI)
2. Sets up an in-memory filesystem (ZeroFs) with PGDATA, config, extensions
3. Instantiates the WASM module via Chicory (AOT-compiled to JVM bytecode)
4. Calls `pgl_initdb()` to create the database cluster
5. Calls `pgl_backend()` to initialize the backend (traps on OpenPipeStream — expected, safe to ignore)
6. Enables wire protocol via `use_wire(1)`
7. Performs the full PostgreSQL wire protocol handshake (StartupMessage, MD5 auth, ReadyForQuery)
8. Sends `SELECT 1;` as a wire protocol Query message
9. Receives and parses the DataRow response via CMA shared memory

### How to run it

```bash
cd chicory2

# 1. Build core modules
./mvnw install -pl annotations/annotations,annotations/processor,wasm,runtime,wasi,compiler,log,wasm-tools,wasm-corpus,compiler-maven-plugin -DskipTests

# 2. Run the PGLite test
./mvnw test -pl machine-tests -Dtest=MachinesTest#shouldRunPGLite
```

### WASM archive location

```
/home/andreatp/workspace/pglite4j/wasm-build/output/sdk-dist/pglite-wasi.tar.xz
```

Contains: `pglite.wasi` (24 MB), postgres/initdb binaries, share/postgresql configs, extensions (plpgsql, dict_snowball), timezone data.

---

## Key Insight: PGlite Speaks the Wire Protocol

PGlite **does** implement the PostgreSQL wire protocol natively inside the WASM module. The bytes going in and out are standard PostgreSQL v3 protocol messages. This means a Java socket server only needs to act as a **transparent byte shuttle** — no protocol parsing required in the Java layer.

### WASM Exports

| Export | Signature | Description |
|--------|-----------|-------------|
| `pgl_initdb()` | `() -> i32` | Create database cluster |
| `pgl_backend()` | `() -> void` | Initialize backend (may trap — expected) |
| `use_wire(state)` | `(i32) -> void` | Enable (1) or disable (0) wire protocol mode |
| `interactive_write(size)` | `(i32) -> void` | Signal input message size; clears output (`cma_wsize=0`) |
| `interactive_read()` | `() -> i32` | Returns output message size (`cma_wsize`) |
| `interactive_one()` | `() -> void` | Process one interaction cycle |
| `get_channel()` | `() -> i32` | Get transport mode: `>=0` = CMA, `<0` = file |
| `get_buffer_addr(fd)` | `(i32) -> i32` | Buffer address: `1 + (buffer_size * fd)` |
| `get_buffer_size(fd)` | `(i32) -> i32` | Buffer size: `(CMA_MB * 1024 * 1024) / CMA_FD` |
| `clear_error()` | `() -> void` | Clear PostgreSQL error state, reset transaction |
| `pgl_shutdown()` | `() -> void` | Shutdown PostgreSQL |
| `pgl_closed()` | `() -> i32` | Check if closed |

### CMA (Channel Memory Access) Communication Flow

```
JDBC Driver  <-->  Java Socket  <-->  WASM Linear Memory  <-->  PostgreSQL (in WASM)

                   wireSendCma:
                   1. use_wire(1)
                   2. memory.write(addr, rawBytes)
                   3. interactive_write(rawBytes.length)

                   interactive_one()  <-- process the message

                   wireRecvCma:
                   1. len = interactive_read()
                   2. response = memory.read(addr + pendingLen + 1, len)
                   3. interactive_write(0)  <-- clear state
```

**Memory layout:**
- Input written at: `buffer_addr` (where `buffer_addr = 1 + (buffer_size * fd)`)
- Output read from: `buffer_addr + pending_wire_len + 1`
- Buffer size: 12 MB (`CMA_MB=12`, `CMA_FD=1`)
- Max single message: 16 KB (`FD_BUFFER_MAX=16384`) — larger messages overflow to file transport

### CMA vs File Transport

Both modes use identical PostgreSQL wire protocol bytes; they differ only in how bytes move between host and WASM:

| Mode | When | How |
|------|------|-----|
| **CMA** (`channel >= 0`) | Messages fit in buffer | Direct WASM memory read/write |
| **File** (`channel < 0`) | CMA buffer overflow | Pseudo-socket files: `.s.PGSQL.5432.in` / `.out` |

The C code in `interactive_one.c` (lines 669-712) determines which mode to use based on whether `sockfiles` is true and whether `SOCKET_DATA > 0`.

---

## Reference Implementations

### 1. pglite-oxide (Rust)

**Location:** `/home/andreatp/workspace/pglite-oxide/src/interactive.rs`

The Rust reference implementation is the most complete and closest to what pglite4j should do.

**Key types:**
```rust
enum Transport {
    Cma { pending_wire_len: usize },
    File { sinput: PathBuf, slock: PathBuf, cinput: PathBuf, clock: PathBuf },
}
```

**Key functions:**

- `InteractiveSession::new()` (lines 582-690) — Full init sequence: `_start()`, `pgl_initdb()`, `pgl_backend()`, get channel/buffer, select transport
- `send_wire(payload)` (lines 746-778) — Write to WASM memory (CMA) or file, call `interactive_write(len)`
- `try_recv_wire()` (lines 780-798) — Dispatcher: CMA or file receive
- `try_recv_wire_cma()` (lines 800-852) — Read from `buffer_addr + pending + 1`, validate bounds, clear state via `interactive_write(0)`
- `forward_wire(payload)` (lines 886-903) — Main loop: send, then `collect_replies()` / `run_once()` up to 256 ticks until no more data
- `drain_wire()` (lines 865-884) — Drain pending data (128 ticks)
- `ensure_handshake()` (lines 968-988) — Full startup: clear pending, send StartupMessage, handle auth (MD5/cleartext), wait for ReadyForQuery ('Z')
- `collect_replies()` — Calls `try_recv_wire()` and appends to reply vec

**forward_wire pattern (the core loop):**
```rust
fn forward_wire(&mut self, payload: &[u8]) -> Result<Vec<Vec<u8>>> {
    if !payload.is_empty() {
        self.use_wire(true)?;
        self.send_wire(payload)?;
    }
    let mut replies = Vec::new();
    for _ in 0..256 {
        let produced_before = self.collect_replies(&mut replies)?;
        self.run_once()?;
        let produced_after = self.collect_replies(&mut replies)?;
        if !produced_before && !produced_after { break; }
    }
    Ok(replies)
}
```

### 2. pglite-socket (TypeScript)

**Location:** `/home/andreatp/workspace/pglite/packages/pglite-socket/src/index.ts`

A Node.js TCP server that bridges sockets to PGlite. This is the exact pattern pglite4j should replicate in Java.

**Core of `PGLiteSocketHandler.handleData()`** (line 185):
```typescript
const result = await this.db.execProtocolRaw(new Uint8Array(data));
this.socket.write(Buffer.from(result));
```

That's it — receive raw bytes from socket, pass to PGlite, write response back. The `execProtocolRaw()` method internally handles CMA/file transport, the `interactive_write`/`interactive_one`/`interactive_read` cycle, and the handshake.

**`PGLiteSocketServer`** manages:
- TCP `ServerSocket` on configurable host:port or Unix socket
- Connection queuing (PGlite is single-connection, like SQLite)
- Exclusive locking via `db.runExclusive()`
- Timeout for queued connections (default 60s)

**Server script** (`/home/andreatp/workspace/pglite/packages/pglite-socket/src/scripts/server.ts`):
```bash
pglite-server --db memory:// --port 5432 --host 127.0.0.1
```

### 3. PGlite C internals

**Location:** `/home/andreatp/workspace/pglite/postgres-pglite/pglite-wasm/interactive_one.c`

Key details from the C code:

- `interactive_write(size)` sets `cma_rsize = size` and clears `cma_wsize = 0`
- `interactive_read()` returns `cma_wsize` (the output size)
- `use_wire(state)` toggles between wire mode (`is_wire=true, is_repl=false`) and REPL mode
- `interactive_one()` handles:
  - Startup packet detection (`peek == 0` -> `startup_auth()`)
  - Password packet detection (`peek == 112` aka `'p'` -> `startup_pass(true)`)
  - Normal wire messages via `SocketBackend(inBuf)`
  - ReadyForQuery emission
  - CMA vs file output routing
- Auth uses hardcoded MD5 salt: `{0x01, 0x23, 0x45, 0x56}` — password checking is effectively skipped (`recv_password_packet` reads it but the TODO for `CheckMD5Auth` is not implemented)
- Channel assignment after processing: `channel = cma_rsize + 2` for CMA, `channel = -1` for file transport

---

## Architecture Plan

### Single Module

```
pglite4j/
  src/main/java/com/dylibso/pglite4j/
    PgLiteDriver.java         # JDBC Driver — the public API (just this)
  src/main/resources/
    META-INF/services/java.sql.Driver
    pglite-wasi.tar.xz        # bundled PostgreSQL WASM binary
  wasm-build/                  # PGlite WASI build scripts (already exists)
```

Everything is internal to the driver. No separate modules, no public API beyond the JDBC URL. The WASM lifecycle, CMA communication, TCP socket bridge — all private implementation details inside `PgLiteDriver`.

### How it works

```
DriverManager.getConnection("jdbc:pglite:memory://")
        │
        ▼
PgLiteDriver.connect(url, props)
        │
        ├── First call for this URL?
        │     ├── Extract pglite-wasi.tar.xz from classpath to temp dir
        │     ├── Boot WASM instance (pgl_initdb, pgl_backend, handshake)
        │     ├── Start internal ServerSocket on auto-selected free port
        │     ├── Register JVM shutdown hook for cleanup
        │     └── Cache instance keyed by URL
        │
        ├── Delegate to pgjdbc:
        │     return pgjdbcDriver.connect(
        │         "jdbc:postgresql://localhost:<port>/template1",
        │         props
        │     );
        │
        └── Subsequent calls → reuse cached instance, just delegate to pgjdbc
```

**URL scheme:** `jdbc:pglite:<data-path>`
- `jdbc:pglite:memory://` — in-memory database (fresh each JVM)
- `jdbc:pglite:/tmp/mydb` — persistent data directory

**ServiceLoader registration:** `META-INF/services/java.sql.Driver` containing:
```
com.dylibso.pglite4j.PgLiteDriver
```

**Driver sketch:**

```java
public class PgLiteDriver implements java.sql.Driver {

    static {
        try {
            DriverManager.registerDriver(new PgLiteDriver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static final ConcurrentHashMap<String, ManagedInstance> instances = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            instances.values().forEach(ManagedInstance::close)
        ));
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:pglite:");
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        String dataPath = url.substring("jdbc:pglite:".length());
        ManagedInstance managed = instances.computeIfAbsent(dataPath, ManagedInstance::boot);
        return DriverManager.getConnection(managed.getJdbcUrl(), info);
    }

    // --- everything below is private implementation detail ---

    private static class ManagedInstance {
        // Bundles: WASM instance + CMA bridge + TCP socket server
        // All the code from PgLiteInstance/PgLiteServer lives here as private methods
    }
}
```

**Usage — just swap the JDBC URL:**

```java
// Plain JDBC
Connection conn = DriverManager.getConnection("jdbc:pglite:memory://");

// Spring Boot (application-test.properties)
spring.datasource.url=jdbc:pglite:memory://

// HikariCP
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:pglite:memory://");
DataSource ds = new HikariDataSource(config);

// Quarkus (application.properties)
quarkus.datasource.jdbc.url=jdbc:pglite:memory://

// Flyway, Liquibase, jOOQ, MyBatis — all work transparently
```

---

## Implementation Steps

### Phase 1: Get the driver working end-to-end

- [x] Create Maven project with chicory dependencies (core module exists)
- [x] Move the wire protocol / CMA logic into `PGLite.java` + `PgWireCodec.java`
- [x] Implement `execProtocolRaw()` following the pglite-oxide pattern
- [x] Wire init sequence: extract pgdata, setup ZeroFS, WASM instance, `pgl_initdb`, `pgl_backend`, handshake
- [x] Bundle pgdata as classpath resources, auto-extract to ZeroFS
- [x] Test: `PGLite.query("SELECT 1")` works end-to-end
- [x] WASM build pipeline: wasmtime + wizer + wasi-vfs + wasm-opt (single `make build`)
- [ ] **JDBC driver**: `PgLiteDriver` class with `jdbc:pglite:` URL, ServiceLoader registration
- [ ] **JDBC driver**: Internal `ServerSocket` on auto-selected free port, raw byte pass-through
- [ ] **JDBC driver**: Delegation to pgjdbc for the actual JDBC implementation
- [ ] **JDBC driver**: Lazy singleton boot, JVM shutdown hook cleanup
- [ ] Test: `DriverManager.getConnection("jdbc:pglite:memory://")` -> `SELECT 1` via pgjdbc

### Phase 2: Validate with real-world usage

- [ ] Test with `psql` connecting to the internal socket
- [ ] Test with HikariCP connection pool
- [ ] Test with Flyway migrations
- [ ] Handle CMA overflow -> file transport fallback
- [ ] Connection queuing (serialize access — PGlite is single-connection)

### Phase 3: Polish

- [ ] Error handling & recovery (`clear_error()`)
- [ ] Performance: AOT compilation of the WASM module for fast startup
- [ ] Consider: bypass TCP entirely with a custom `java.net.SocketImpl` that calls `forwardWire` in-process

---

## Open Questions

1. **File transport fallback:** The current test only uses CMA. Need to verify file transport works when messages exceed the CMA buffer (16 KB single message / 12 MB total). The pglite-oxide code handles this transparently — we should too.

2. **Socket read framing:** When reading from the TCP socket, we need to figure out when a complete PG message has arrived before passing it to PGlite. The wire protocol has length-prefixed messages, so we may need minimal framing logic (read tag byte + 4-byte length, then read that many bytes). Alternatively, we might be able to pass partial data and let PGlite handle buffering internally — needs investigation. The TypeScript `pglite-socket` seems to just pass whatever bytes arrive from the socket directly to `execProtocolRaw`, suggesting PGlite handles partial reads internally.

3. **Concurrency model:** PGlite is single-threaded/single-connection. The socket server should accept connections sequentially. For test use cases this is fine, but worth documenting.

4. **WASM binary size & startup time:** The 24 MB WASM binary compiles to JVM bytecode via Chicory's AOT compiler. Some functions exceed JVM method size limits and fall back to the interpreter. Need to measure startup time and consider caching the compiled module.

5. **Extensions:** PGlite ships with plpgsql and dict_snowball. Adding more extensions means rebuilding the WASM binary.

---

## File References

| What | Path |
|------|------|
| Working Java test | `chicory2/machine-tests/src/test/java/com/dylibso/chicory/testing/MachinesTest.java:266-634` |
| Build/run instructions | `chicory2/machine-tests/PGLITE-STATUS.md` |
| WASM binary archive | `pglite4j/wasm-build/output/sdk-dist/pglite-wasi.tar.xz` |
| PGlite C internals | `pglite/postgres-pglite/pglite-wasm/interactive_one.c` |
| pglite-oxide (Rust ref) | `pglite-oxide/src/interactive.rs` |
| pglite-socket (TS ref) | `pglite/packages/pglite-socket/src/index.ts` |
| pglite-socket server | `pglite/packages/pglite-socket/src/scripts/server.ts` |

All paths relative to `/home/andreatp/workspace/`.


Additional notes:

- Try to keep all the resources self-contained in the JAR
- use ZeroFS instead of the tmp folder so that everything is fully portable
- Automate the unzip via `make unpack` in wasm-build/, and find a way to automate including needed files in the bundle
- Follow sqlite4j code structure when in doubt — keep it familiar
- follow up: optimize startup times with wasm-opt and wizer, and try wasi-sdk?

---

## Design Decisions

### Embedded resources — no external pgDistDir

All PGlite resources (WASM binary, share/, extensions) must be embedded
in the JAR. There is no user-facing configuration for a distribution
directory. The builder takes no path argument — everything is loaded from
the classpath at build time (via Chicory's AOT compiler) and from
embedded resources at runtime (via ZeroFS populated from classpath).

---

## Startup Optimization Plan — wasi-vfs + wizer

### Problem

Current startup is slow (~58 s in tests) because:
1. Filesystem setup: copying the PG distribution into ZeroFS at runtime
2. `pgl_initdb()`: creates the database cluster from scratch every boot
3. `pgl_backend()`: initializes the PostgreSQL backend
4. Wire protocol handshake (auth, ReadyForQuery)

Steps 1-4 are identical every time for a fresh in-memory database. They
can all be done once at build time and snapshotted.

### Solution: two build-time tools

#### 1. wasi-vfs — embed the filesystem into the WASM binary

**What:** [wasi-vfs](https://github.com/kateinoigakukun/wasi-vfs) packs
a host directory tree into a WASM binary as a read-only virtual
filesystem. At runtime the WASM module sees normal files via WASI
fd_read/path_open — no host directory mapping needed.

**How it helps:** Eliminates the runtime ZeroFS copy of share/,
extensions, timezone data, config files. These become part of the WASM
binary itself.

**Build step:**
```bash
wasi-vfs pack pglite.wasi \
    --mapdir /tmp/pglite::wasm-build/output/tmp/pglite \
    -o pglite-packed.wasi
```

**Constraint:** wasi-vfs produces a read-only filesystem. PGDATA (the
mutable database cluster) still needs a writable filesystem at runtime
(ZeroFS). This is fine — wasi-vfs handles the static resources, ZeroFS
handles the mutable PGDATA.

#### 2. wizer — pre-initialize the WASM module

**What:** [Wizer](https://github.com/bytecodealliance/wizer) executes a
WASM module's initialization function, then snapshots the entire memory
state (globals, linear memory) into a new WASM binary. The next
instantiation starts from the warm snapshot.

**How it helps:** Runs `pgl_initdb()`, `pgl_backend()`, and the wire
protocol handshake once at build time. The resulting binary boots
directly into a ReadyForQuery state.

**Build step:**
```bash
wizer pglite-packed.wasi \
    --allow-wasi \
    --init-func _start \
    --keep-init-func false \
    -o pglite-initialized.wasi
```

This requires an initialization entry point in PGlite that:
1. Calls `pgl_initdb()`
2. Calls `pgl_backend()` (catches the expected trap)
3. Enables wire mode, performs the handshake
4. Returns — wizer snapshots everything at this point

### Reference implementations

| Project | What they do | Reference |
|---------|-------------|-----------|
| **trino-wasm-python** | wasi-vfs packs Python stdlib into WASM, then wizer snapshots the initialized CPython interpreter. Pipeline: compile → `wasi-vfs pack` → `wizer` → deploy. Uses `--allow-wasi --init-func _start --keep-init-func false`. | [trinodb/trino-wasm-python](https://github.com/trinodb/trino-wasm-python) |
| **lumis4j** | Rust syntax highlighter compiled to WASM. Wizer pre-loads all language parsers via `wizer.initialize()` export. Then Chicory AOT compiles to JVM bytecode. Pipeline: `cargo build` → `wizer --allow-wasi` → `wasm-opt -Oz` → `chicory-compiler-maven-plugin`. | [roastedroot/lumis4j](https://github.com/roastedroot/lumis4j) |

### Proposed build pipeline

```
1. Docker build (existing)
   └── pglite.wasi (24 MB, raw)

2. wasi-vfs pack
   ├── Embed: share/postgresql/, lib/postgresql/, password, timezonesets
   ├── Mount point: /tmp/pglite
   └── Output: pglite-packed.wasi

3. wizer pre-initialize
   ├── Run: pgl_initdb + pgl_backend + handshake
   ├── Snapshot: warm memory state with initialized cluster
   └── Output: pglite-initialized.wasi

4. wasm-opt (optional, binaryen)
   ├── Flags: -Oz --strip-debug
   └── Output: pglite-opt.wasi (size-optimized)

5. Chicory AOT compile (existing, maven plugin)
   ├── Input: pglite-opt.wasi
   └── Output: PGLiteModule.java (JVM bytecode)
```

### Impact on Java code

With wasi-vfs + wizer, the Java `PGLite` constructor simplifies to:
- No more archive extraction
- No more copying files into ZeroFS (static resources are in the binary)
- ZeroFS only needed for PGDATA (mutable, writable)
- No `pgl_initdb()` / `pgl_backend()` / handshake calls — already done
- Constructor just creates the Instance and it's ready for queries

### Status: WORKING — wasi-vfs + wizer + wasmtime + wasm-opt

The full build pipeline is implemented and working in a single
`make build` run. The key insight was separating initdb (which traps
via `pg_proc_exit(66)`) from the wizer snapshot step.

**Build pipeline (in Docker):**
1. Compile PostgreSQL to WASI → `pglite.wasi`
2. **wasmtime pre-init**: runs `main()` with `INITDB_ONLY=1` to
   populate `/pgdata` via initdb. `pg_proc_exit(66)` terminates
   wasmtime gracefully (unlike wizer which needs a clean return).
3. **wizer snapshot**: runs `wizer_initialize()` which detects
   `/pgdata/PG_VERSION`, skips initdb, calls `pgl_backend()`, and
   returns cleanly. Wizer snapshots the warm memory state.
4. **wasi-vfs pack**: embeds `/tmp/pglite/share` and `/tmp/pglite/lib`
   as read-only filesystem inside the WASM binary.
5. **wasm-opt**: optimizes with `${WASM_OPT_FLAGS}` (default `-Oz --strip-debug`).
6. **tar.xz**: packages `pglite.wasi` + `/pgdata` into archive.

**PGDATA handling**: wasi-vfs only embeds `share/` and `lib/` (read-only
PostgreSQL distribution files). The mutable PGDATA cluster is NOT
embedded in wasi-vfs — it's packaged separately in the tar.xz archive
and provided at runtime via ZeroFS. This avoids the earlier problem
where wasi-vfs intercepted all filesystem calls including PGDATA paths.

**TODO — verify DDL/DML**: The wasi-vfs approach now only embeds
`share/` and `lib/` subdirectories (not the whole `/tmp/pglite` tree).
Need to verify that DDL (CREATE TABLE, etc.) works correctly with
PGDATA served via ZeroFS while share/lib come from wasi-vfs.

### Hardcoded variables

The following values are hardcoded in three places and **must stay in
sync**: `pg_main.c` (`wizer_initialize`), `PGLite.java` (constants),
and `build.sh` (wizer `env -` block).

| Variable | Value | Used by |
|----------|-------|---------|
| `PREFIX` | `/tmp/pglite` | Install prefix for PG binaries, share, libs |
| `PGDATA` | `/pgdata` | Mutable database cluster directory (wizer uses `/pgdata`, Java may use `/pgdata` or `/tmp/pglite/base`) |
| `PGUSER` | `postgres` | Superuser name |
| `PGDATABASE` | `template1` | Default database |
| `PATH` | `/tmp/pglite/bin` | Binary search path |
| `PGSYSCONFDIR` | `/tmp/pglite` | Server config directory |
| `PGCLIENTENCODING` | `UTF8` | Client encoding |
| `LC_CTYPE` | `en_US.UTF-8` | Locale for character classification |
| `TZ` / `PGTZ` | `UTC` | Timezone |
| `REPL` | `N` | Disable interactive REPL |
| `ENVIRONMENT` | `wasm32_wasi_preview1` | WASI platform identifier |
| `MODE` | `REACT` | PGlite operational mode |

**Why hardcoded:** The `WASM_PREFIX` macro in `wasm_common.h` has a bug
where `#undef PG_PREFIX` runs before lazy macro expansion of
`WASM_PREFIX`, causing it to resolve to the literal string `"PG_PREFIX"`
instead of `"/tmp/pglite"`. Additionally, wizer doesn't forward host
environment variables to the WASI module by default (requires
`--inherit-env true`). Hardcoding avoids both issues.

### Key implementation details

- **wizer replaces `_start` with `unreachable`**: After pre-initialization,
  `_start`/`main()` must not be called. Java uses `withStart(false)` on
  the Chicory Instance.Builder.
- **Memory size**: The wizer snapshot expands initial memory to ~4029 pages
  (~252 MB). Java uses `withMemoryLimits(new MemoryLimits(4029))`.
- **8 functions use interpreter fallback**: Functions that exceed JVM method
  size limits fall back to the Chicory interpreter via
  `<interpreterFallback>WARN</interpreterFallback>`.
- **wizer needs writable `/tmp`**: The initdb `pgl_popen` writes to
  `/tmp/initdb.boot.txt` and `/tmp/initdb.single.txt`. The wizer
  invocation maps `--mapdir /tmp::/tmp/wizer-tmp` for this.

### Resolved open questions

1. **wizer + PGlite compatibility:** Resolved. `pgl_backend()` does NOT
   trap (the `proc_exit(66)` is a fake shutdown that returns cleanly).
   A `wizer.initialize` export was added to `pg_main.c` that runs the
   full init sequence: `pgl_initdb()`, `pgl_backend()`,
   `interactive_write(0)`, `interactive_one()`.

2. **PGDATA separation:** Resolved. wasi-vfs embeds the static
   distribution (share/, lib/, password). The writable PGDATA is
   provided at runtime via ZeroFS. The wizer snapshot includes a
   pre-initialized PGDATA that is also packed into the tar archive.

3. **wasi-vfs + wasi-sdk compatibility:** Resolved. wasi-sdk 25 +
   wasi-vfs 0.6.2 work together. `libwasi_vfs.a` is linked at compile
   time, and `wasi-vfs pack` embeds the filesystem post-link.

4. **Binary size:** The wizer-initialized binary is ~126 MB (includes
   the full memory snapshot). After wasm-opt, this is reduced somewhat.
   The tar.xz archive compresses well.
