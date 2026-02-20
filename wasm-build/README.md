# pglite WASI Build

Derived from: https://github.com/electric-sql/pglite-build

Self-contained Docker build for compiling PostgreSQL to WebAssembly (WASI target) as pglite.

All toolchain components are downloaded from upstream sources at build time -- no vendored binaries or third-party SDKs required.

## Quick Start

```bash
# 1. Build WASM (requires Docker)
./build.sh

# 2. Unpack into wasm-dist
cd ../wasm-dist && ./unpack.sh && cd ../wasm-build

# 3. Build Java
cd .. && mvn install
```

### Build

```bash
./build.sh
```

This builds the Docker image, compiles PostgreSQL to WASI, and produces:

| File | Description |
|------|-------------|
| `output/sdk-dist/pglite.wasi` | pglite WASI binary (~23 MB) |
| `output/sdk-dist/pg_dump.wasi` | pg_dump WASI binary |
| `output/sdk-dist/pglite-wasi.tar.xz` | Distribution archive with all runtime files |

## Clean Rebuild

```bash
CLEAN=true ./build.sh
```

Or manually:

```bash
docker run --rm -v "$(pwd)/output:/data" alpine:3.21 rm -rf /data/sdk-build /data/sdk-dist /data/pglite
./build.sh
```

## Adding Patches

Patches are applied on top of the [electric-sql/postgres-pglite](https://github.com/electric-sql/postgres-pglite) fork. They live in `patches/` under three directories, applied in this order:

1. **`postgresql-emscripten/`** -- shared WASM platform support (emscripten + WASI)
2. **`postgresql-wasi/`** -- WASI-specific adaptations
3. **`postgresql-pglite/`** -- pglite application-level changes

The postgres-pglite fork already includes all current patches, so they are
only applied when building against vanilla PostgreSQL. To add a new patch:

### 1. Create the patch

```bash
# Clone the fork and make your change
git clone --branch REL_17_5_WASM-pglite \
    https://github.com/electric-sql/postgres-pglite pg-modified

cd pg-modified
# ... edit src/backend/foo/bar.c ...

# Generate the diff (paths must be relative to source root)
git diff > ../patches/postgresql-wasi/src-backend-foo-bar.c.diff
```

The diff must use `-p1` format (one directory prefix stripped). The naming
convention is the file path with `/` replaced by `-`.

### 2. Force re-patching

Delete the cloned source tree so the next build re-clones and re-applies
patches:

```bash
rm -rf postgresql-src
```

### 3. Rebuild

```bash
CLEAN=true ./build.sh
```

### Patch tips

- Put WASI-only changes in `postgresql-wasi/`
- Put pglite application changes in `postgresql-pglite/`
- Guard new code with `#if defined(__wasi__)` or `#if defined(__EMSCRIPTEN__) || defined(__wasi__)`
- The WASI sysroot has patched headers for missing POSIX APIs (see `sdk/sysfix/`)
- Runtime stubs for fork/pipe/signals/semaphores are in `wasm-build/sdk_port-wasi/`
- Additional POSIX stub headers are in `sdk/hotfix/`

## Repository Structure

```
.
├── build.sh                  # Build script (run this)
├── docker/
│   ├── Dockerfile            # Build environment (downloads wasi-sdk, cross-compiles zlib)
│   └── entrypoint.sh         # Clones PostgreSQL, applies patches, runs build
├── sdk/
│   ├── hotfix/               # POSIX stub headers (pwd.h, netdb.h, dlfcn.h, etc.)
│   │   ├── patch.h           # Master compatibility shim (force-included)
│   │   ├── sdk_socket.c      # File-based Unix socket emulation
│   │   └── ...
│   ├── sysfix/               # Sysroot overlays (replace upstream wasi-sdk headers)
│   │   ├── bits/alltypes.h   # Adds pthread_barrier_t for PostgreSQL
│   │   ├── __struct_sockaddr_un.h  # Adds sun_path to sockaddr_un
│   │   └── ...
│   └── bin/                  # Cross-compilation toolchain (see "Compiler Wrappers" below)
│       ├── wasi-c            # CC wrapper: clang + WASI flags + argument filtering
│       ├── wasi-c++          # Symlink to wasi-c (C++ mode)
│       ├── wasi-cpp          # Symlink to wasi-c (preprocessor mode)
│       ├── wasi-configure    # Configure wrapper (--host/--target for cross-compilation)
│       └── wasisdk_env.sh    # Environment setup (CC, AR, PATH, etc.)
├── patches/
│   ├── postgresql-emscripten/  # Shared WASM patches
│   ├── postgresql-wasi/        # WASI-specific patches
│   └── postgresql-pglite/      # pglite-specific patches
├── pglite-wasm/ (cloned)       # pglite entry point and linking (from postgres-pglite fork)
├── wasm-build/
│   ├── build-pgcore.sh        # PostgreSQL core compilation
│   ├── build-ext.sh           # Extension compilation
│   ├── sdk_port.h             # POSIX compatibility layer
│   ├── sdk_port-wasi/         # WASI runtime stubs
│   └── ...
├── wasm-build.sh              # Main build orchestrator
├── wasmfs.txt                 # Files included in the distribution archive
└── .github/workflows/build.yml  # CI workflow
```

## How the Build Works

1. **Docker image** (`docker/Dockerfile`):
   - Debian bookworm-slim with build tools (make, gcc, bison, flex, etc.)
   - Downloads [wasi-sdk 25](https://github.com/WebAssembly/wasi-sdk) (clang/lld targeting WASM)
   - Patches the wasi-sdk sysroot with `sdk/sysfix/` headers for missing POSIX types
   - Installs `sdk/hotfix/` stub headers and the `sdk/bin/` compiler wrappers
   - Cross-compiles [zlib](https://github.com/madler/zlib) for WASI

2. **Entrypoint** (`docker/entrypoint.sh`):
   - Clones the postgres-pglite fork into `postgresql-src/`
   - Applies patches from `patches/`
   - Copies POSIX runtime stubs (`sdk_port-wasi/`) into the install prefix
   - Runs `wasm-build.sh`

3. **Build** (`wasm-build.sh` + `wasm-build/build-pgcore.sh`):
   - Sources `wasisdk_env.sh` to set `CC=wasi-c`, `AR`, `PATH`, etc.
   - Runs PostgreSQL's `configure` via `wasi-configure` (adds `--target=wasm32-wasi-preview1`)
   - Runs `make install` -- PostgreSQL's Makefile invokes `$CC` (`wasi-c`) for each source file
   - Links `pglite.wasi` and `pg_dump.wasi` via `pglite-wasm/build.sh`
   - Packages the result into `pglite-wasi.tar.xz`

## Compiler Wrappers

PostgreSQL's build system (`configure` + `make`) invokes the C compiler
hundreds of times with flags that assume a POSIX host. The `sdk/bin/`
wrappers adapt this for WASI cross-compilation. This is the same pattern
used by emcc, android-ndk, and musl-gcc.

**`wasi-c`** (`$CC`) -- the main wrapper. On each invocation it:
- Filters out flags that break WASI (`-pthread`, `-latomic`, `-I/usr/*`, `-L/usr/*`)
- Adds WASI emulation defines (`-D_WASI_EMULATED_MMAN`, etc.)
- Force-includes `patch.h` (POSIX stubs) during builds, but **skips it
  during `configure`** so that feature-detection probes see clean libc behavior
- Adds link-time flags (`-lwasi-emulated-*`, `-L$PREFIX/lib`) only when
  actually linking (not for `-c` compile-only invocations)
- Handles `-shared` mode for PostgreSQL's encoding conversion modules

`wasi-c++` and `wasi-cpp` are symlinks to `wasi-c` -- it detects the
invoked name and switches to `clang++` or `clang -E` accordingly.

**`wasi-configure`** -- one-liner that sets `CONFIGURE=true` (tells
`wasi-c` to skip `patch.h`) and adds `--host`/`--target` flags.

**`wasisdk_env.sh`** -- sets `CC`, `CXX`, `CPP`, `AR`, `RANLIB`, `PATH`,
`PKG_CONFIG_*`, etc. Sourced by `entrypoint.sh` and the generated
`pg-make.sh` rebuild script.

## Upstream Dependencies

All tools are downloaded from their official upstream sources:

| Tool | Version | Source |
|------|---------|--------|
| wasi-sdk | 25.0 | [WebAssembly/wasi-sdk](https://github.com/WebAssembly/wasi-sdk) |
| zlib | 1.3.1 | [madler/zlib](https://github.com/madler/zlib) |

## CI

The GitHub Actions workflow (`.github/workflows/build.yml`) runs on every
push/PR to `main`. It builds the Docker image, runs the compilation, and
uploads the WASI binaries as artifacts.
