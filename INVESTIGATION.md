# pglite4j — Current Investigation Notes

## Current State (2026-02-23)

We are running **without wizer and without wasi-vfs**. The raw WASM binary
(~24 MB, linked without `libwasi_vfs.a`) is at `wasm-build/output/pglite.wasi`.
All PostgreSQL files (share/, lib/, bin/, base/) are provided via ZeroFS from
classpath resources at runtime.

The Java init sequence calls `_start` (which runs `main()` → `main_pre()` →
returns because `REPL=N`), then `pgl_initdb()`, then `pgl_backend()`.

**SELECT 1 used to work with the wizer'd binary. We are now debugging the
raw binary (no wizer) to get DDL (CREATE TABLE) working.**

---

## RESOLVED: `pgl_backend()` now works (2026-02-23)

The `pgl_backend()` call previously hit `abort()` → WASM trap during
initialization. This was fixed by:

1. Removing the `--no-stack-first` linker flag (`build.sh.diff`)
2. Building without wizer and wasi-vfs (raw binary)
3. Adding debug prints in `pgl_mains.c.diff` and enhanced error reporting in
   `elog.c.diff`

### Current working output
```
pgl_initdb returned 14
PGLite: pgl_backend returned normally
SELECT 1 => 1
CREATE TABLE works
INSERT + SELECT works
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

### Why we can't see the actual error message

`whereToSendOutput` is `DestNone` during initialization (it only gets set to
`DestDebug` or `DestRemote` later). So `EmitErrorReport()` has nowhere to
send the message — it's silently lost.

---

## Root Cause Analysis

### Path taken through the C code

1. `pgl_initdb()` finds existing PGDATA with `PG_VERSION` → sets `async_restart=0`
2. `pgl_backend()` with `async_restart=0` → calls `main_post()` then
   `AsyncPostgresSingleUserMain()` (pgl_mains.c:264)
3. `AsyncPostgresSingleUserMain` does full backend init: `InitStandaloneProcess`,
   `InitializeGUCOptions`, `SelectConfigFiles`, `checkDataDir`,
   `CreateDataDirLockFile`, `LocalProcessControlFile`,
   `CreateSharedMemoryAndSemaphores`, `InitProcess`, `BaseInit`
4. Something between `CreateSharedMemoryAndSemaphores` and `InitPostgres`
   raises `ereport(ERROR, ...)`

### Where the abort comes from

File: `wasm-build/patches/postgresql-pglite/src-backend-utils-error-elog.c.diff`

```c
// In errfinish(), when edata->elevel >= ERROR:
recursion_depth--;
#if defined(__wasi__)
    fprintf(stderr, "# 547: PG_RE_THROW(ERROR : %d) ignored\r\n", recursion_depth);
    abort();        // ← THIS CAUSES THE WASM TRAP
#else
    PG_RE_THROW();  // ← Normal path: siglongjmp to error handler
#endif
```

### Why `pgl_sjlj.c` is disabled on WASI

File: `wasm-build/pglite-wasm/pgl_sjlj.c`

On WASI, the entire `sigsetjmp`/`siglongjmp` error handler is disabled:
```c
#if defined(__wasi__)
    PDEBUG("# 2:" __FILE__ ": sjlj exception handler off");
#else
    if (sigsetjmp(local_sigjmp_buf, 1) != 0) {
        // error recovery code...
    }
    PG_exception_stack = &local_sigjmp_buf;
#endif
```

This means `PG_exception_stack` is never set. When `PG_RE_THROW()` would
try `siglongjmp(*PG_exception_stack, 1)`, it would crash (NULL deref).
The `abort()` replaces this crash with a controlled trap.

---

## Strategy: Fix Root Causes, NOT Re-enable Exception Handling

**DO NOT re-enable sigsetjmp/siglongjmp exception handling** unless
absolutely necessary. Instead:

1. Find which specific function raises the `ereport(ERROR)` during init
2. Fix the root cause so the error doesn't occur on the happy path
3. Adapt the C code to handle the situation gracefully

Re-enabling exception handling would mask bugs and add complexity. The goal
is a clean happy path where no errors are raised during initialization.

---

## How to Build / Test

### Build the WASM binary (Docker)
```bash
cd wasm-build
./build.sh
make unpack
```

### Run the test
```bash
mvn test -pl core -Dtest="PGLiteTest#selectOne" -Dsurefire.useFile=false
```

### Rebuild just pglite.o + relink (faster iteration)

The C files in `wasm-build/pglite-wasm/` are root-owned from Docker.
**Do not modify them directly.** Instead:

1. Create patches in `wasm-build/patches/pglite-wasm/` (applied by
   `docker/entrypoint.sh` during build)
2. Or override files through Docker commands

To recompile just the pglite component:
```bash
docker run --rm \
    -v "$(pwd):/workspace:rw" \
    -v "$(pwd)/wasm-build/output/sdk-build:/tmp/sdk/build:rw" \
    -v "$(pwd)/wasm-build/output/sdk-dist:/tmp/sdk/dist:rw" \
    -v "$(pwd)/wasm-build/output/pglite:/tmp/pglite:rw" \
    --entrypoint bash pglite-wasi-builder:latest -c '
        . /tmp/sdk/wasisdk/wasisdk_env.sh
        . /tmp/pglite/pgopts.sh
        # ... compile and link commands ...
    '
```

### Key files

| File | Description |
|------|-------------|
| `wasm-build/pglite-wasm/pg_main.c` | Main entry, includes all other .c files |
| `wasm-build/pglite-wasm/pgl_mains.c` | `AsyncPostgresSingleUserMain`, `RePostgresSingleUserMain` |
| `wasm-build/pglite-wasm/pgl_sjlj.c` | Error handler (disabled on WASI) |
| `wasm-build/pglite-wasm/interactive_one.c` | Wire protocol handling, `interactive_one()` |
| `wasm-build/patches/postgresql-pglite/src-backend-utils-error-elog.c.diff` | Error reporting patch |
| `core/src/main/java/.../PGLite.java` | Java init sequence |

---

## Next Steps

1. ~~Find and fix root cause of `ereport(ERROR)` during init~~ **DONE** — removed `--no-stack-first`
2. ~~Get CREATE TABLE working~~ **DONE** — all 3 tests pass
3. Revisit wizer + wasi-vfs integration for faster startup
4. Expand JDBC driver coverage (more SQL types, prepared statements, etc.)
5. Clean up debug prints once stable

---

## Previous Findings (wasi-vfs)

- wasi-vfs intercepts ALL WASI filesystem calls regardless of which
  directories are mapped — it's all-or-nothing, never delegates to host WASI
- Specific subdirectory mapping (`--dir share::/tmp/pglite/share`) does NOT
  help — `is_embed=1` confirms global interception
- This blocks PGDATA access (ENOENT for `/tmp/pglite/base/global/pg_control`)
- Three potential fixes documented in PLAN.md; currently bypassed by not
  linking `libwasi_vfs.a`
