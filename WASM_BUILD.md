# pglite WASI Build — Design Notes

## What this is

`wasm-build/` contains a self-contained Docker build that compiles PostgreSQL 17.5 to WebAssembly (WASI target) as **pglite** — a single `pglite.wasi` binary (~24 MB) that runs PostgreSQL in-process.

All toolchain components are downloaded from upstream sources at build time. There are no vendored binaries or third-party SDK bundles.

## Quick start

```bash
cd wasm-build
./build.sh
# outputs: output/sdk-dist/pglite.wasi, pg_dump.wasi, pglite-wasi.tar.xz
```

## Origin story

The original pglite build used [pygame-web/pkg](https://github.com/nicegui/nicegui)'s `pglite-wasm` toolchain, which bundled a customized wasi-sdk, custom Python compiler wrappers, and various patched headers into a large opaque SDK. The build ran under PRoot to fake a cross-compilation sysroot.

This rewrite eliminates all of that:

1. **pygame-web SDK → upstream wasi-sdk 25** — downloaded directly from [WebAssembly/wasi-sdk](https://github.com/WebAssembly/wasi-sdk)
2. **PRoot → Docker** — a standard container replaces the PRoot sandbox
3. **Python compiler wrappers → bash wrappers** — `sdk/bin/wasi-c` is a ~100-line bash script
4. **Vendored pglite source → cloned at build time** — from [electric-sql/postgres-pglite](https://github.com/electric-sql/postgres-pglite) branch `REL_17_5_WASM-pglite`

## Architecture

```
wasm-build/
├── build.sh                  # Host entry point — builds Docker image, runs container
├── docker/
│   ├── Dockerfile            # Build environment (debian + wasi-sdk + wasmtime + wabt + zlib)
│   └── entrypoint.sh         # Container entry — clones PostgreSQL, applies patches, runs build
├── sdk/
│   ├── bin/
│   │   ├── wasi-c            # THE compiler wrapper (C/C++/CPP modes)
│   │   ├── wasi-c++          # Symlink → wasi-c (detects C++ via $0)
│   │   ├── wasi-cpp          # Symlink → wasi-c (preprocessor-only mode)
│   │   ├── emconfigure       # Configure wrapper (sets --host/--target)
│   │   ├── emmake            # Make passthrough
│   │   └── wasisdk_env.sh    # Environment setup (CC, AR, PATH, PKG_CONFIG)
│   ├── hotfix/               # POSIX stub headers for APIs missing from WASI
│   │   ├── patch.h           # Master compatibility shim (force-included via -include)
│   │   ├── sdk_socket.c      # File-based Unix socket emulation
│   │   ├── pwd.h, grp.h, netdb.h, dlfcn.h, ...
│   └── sysfix/               # Sysroot overlays (replace upstream wasi-sdk headers)
│       ├── bits/alltypes.h   # Adds pthread_barrier_t
│       ├── __struct_sockaddr_un.h  # Adds sun_path
│       └── ...
├── patches/
│   ├── postgresql-emscripten/  # Shared WASM patches (emscripten + WASI)
│   ├── postgresql-wasi/        # WASI-specific adaptations
│   └── postgresql-pglite/      # pglite application changes
├── wasm-build/                 # PostgreSQL compilation scripts
│   ├── build-pgcore.sh         # Core PostgreSQL compilation
│   ├── build-ext.sh            # Extension compilation
│   ├── sdk_port.h              # POSIX compatibility layer
│   └── sdk_port-wasi/          # WASI runtime stubs (signals, semaphores, etc.)
├── wasm-build.sh               # Main build orchestrator
└── wasmfs.txt                  # Files included in distribution archive
```

## Key design decisions

### The compiler wrapper (`sdk/bin/wasi-c`)

This is the most critical file. It wraps `clang` from wasi-sdk and handles:

- **Mode detection** — C vs C++ vs preprocessor-only (via `$0` basename)
- **Compile-only vs link** — `-lwasi-emulated-*` and `-z stack-size` only passed when linking (not during `-c`/`-E`/`-S`), otherwise autoconf feature detection breaks
- **Configure mode** — `patch.h` force-include is disabled during `./configure` to avoid polluting feature tests
- **Shared library mode** — adds `-Wl,--allow-undefined` for encoding conversion modules
- **Flag filtering** — strips `-pthread`, `-latomic`, host `/usr/` paths, linker groups

### Sysroot patching (`sdk/sysfix/`)

WASI's libc (wasi-libc) is missing some POSIX types and structs PostgreSQL needs. Rather than patching PostgreSQL source, we overlay a few sysroot headers:

- `bits/alltypes.h` — adds `pthread_barrier_t` (just a typedef, no real threads)
- `__struct_sockaddr_un.h` — adds `sun_path` field
- `setjmp.h` — extends `jmp_buf` for PostgreSQL's error handling
- `sys/mman.h`, `sys/shm.h` — shared memory stubs

**Important**: overlays must be applied to BOTH `wasm32-wasi` and `wasm32-wasip1` sysroot directories (the compiler targets `wasm32-wasip1`).

### Hotfix headers (`sdk/hotfix/`)

Stub headers for POSIX APIs that don't exist in WASI at all: `pwd.h`, `grp.h`, `netdb.h`, `dlfcn.h`, `sys/ipc.h`, etc. These provide the type definitions and function signatures; the actual implementations are no-op stubs in `patch.h`.

`patch.h` is the master shim — force-included via `-include` in every compilation unit (except during `./configure`). It provides:

- `fork()` → returns -1 with `ENOSYS`
- `getpwuid()` → returns a static struct
- Signal handling stubs
- `getrusage()` → zeroed struct
- `sdk_fdtell()` using `__wasi_fd_seek`
- Socket emulation via files (using `sdk_socket.c`)

### Source acquisition

The postgres-pglite fork (`electric-sql/postgres-pglite`, branch `REL_17_5_WASM-pglite`) ships pre-patched — it already includes emscripten/WASI/pglite patches. The `patches/` directory exists for building against vanilla PostgreSQL or for adding new fixes on top.

The `pglite-wasm/` directory (entry point, `pg_main.c`, etc.) is copied from the cloned fork at build time by `entrypoint.sh`. No vendored copy needed.

## Upstream dependencies

| Tool | Version | Purpose |
|------|---------|---------|
| wasi-sdk | 25.0 | Clang/LLD cross-compiler for wasm32-wasip1 |
| wasmtime | 33.0.0 | WASI runtime (runs wasm during build for initdb, etc.) |
| wabt | 1.0.36 | `wasm-objdump` for symbol inspection |
| zlib | 1.3.1 | Cross-compiled for WASI (PostgreSQL dependency) |

## Build outputs

| File | Size | Description |
|------|------|-------------|
| `pglite.wasi` | ~24 MB | pglite WASI binary |
| `pg_dump.wasi` | ~1.8 MB | pg_dump WASI binary |
| `pglite-wasi.tar.xz` | ~5.3 MB | Distribution archive with all runtime files |

## Known non-critical warnings

- `tar` warnings about missing optional files (`llvmjit.so`, `plpgsql--1.0.control`) — these are Emscripten-specific and not built for WASI
- `gzip: extensions-emsdk/*.tar: No such file or directory` — extension packaging is Emscripten-only
- `dirname: missing operand` — cosmetic issue in a path detection script

These don't affect the build output.
