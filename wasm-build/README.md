# pglite WASI Build

Self-contained Docker build for compiling PostgreSQL to WebAssembly (WASI target) as pglite.

All toolchain components are downloaded from upstream sources at build time -- no vendored binaries or third-party SDKs required.

## Quick Start

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
rm -rf postgresql-REL_17_5_WASM-pglite
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
│   ├── Dockerfile            # Build environment (downloads wasi-sdk, wasmtime, wabt)
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
│   └── bin/                  # Compiler wrapper scripts
│       ├── wasi-c            # Bash wrapper for clang (adds WASI flags)
│       ├── emconfigure       # Configure wrapper (--host/--target for cross-compilation)
│       ├── emmake            # Make passthrough
│       └── wasisdk_env.sh    # Environment setup (CC, AR, PATH, etc.)
├── patches/
│   ├── postgresql-emscripten/  # Shared WASM patches
│   ├── postgresql-wasi/        # WASI-specific patches
│   └── postgresql-pglite/      # pglite-specific patches
├── pglite-wasm/ (cloned)       # pglite entry point and linking (from postgres-pglite fork)
├── wasm-build/
│   ├── build-pgcore.sh        # PostgreSQL core compilation
│   ├── build-ext.sh           # Extension compilation
│   ├── sdk.sh                 # SDK validation
│   ├── sdk_port.h             # POSIX compatibility layer
│   ├── sdk_port-wasi/         # WASI runtime stubs
│   └── ...
├── wasm-build.sh              # Main build orchestrator
├── wasmfs.txt                 # Files included in the distribution archive
└── .github/workflows/build.yml  # CI workflow
```

## How the Build Works

1. **Docker image** is built from `docker/Dockerfile`:
   - Debian bookworm-slim with build tools (make, gcc, bison, flex, etc.)
   - Downloads [wasi-sdk 25](https://github.com/WebAssembly/wasi-sdk) (clang/lld for WASM)
   - Patches the wasi-sdk sysroot with `sdk/sysfix/` headers
   - Installs `sdk/hotfix/` stub headers and bash compiler wrappers
   - Cross-compiles [zlib](https://github.com/madler/zlib) for WASI
   - Downloads [wasmtime v33](https://github.com/bytecodealliance/wasmtime) for running WASI binaries during build
   - Downloads [wasm-objdump](https://github.com/WebAssembly/wabt) for symbol inspection

2. **Entrypoint** (`docker/entrypoint.sh`):
   - Clones the pre-patched postgres-pglite fork (includes `pglite-wasm/` entry point)
   - Applies any additional patches from `patches/`
   - Copies SDK port files (POSIX stubs for signals, semaphores, etc.)
   - Runs `wasm-build.sh`

3. **Build** (`wasm-build.sh`):
   - Configures PostgreSQL for WASI cross-compilation
   - Compiles the PostgreSQL backend, utilities, and extensions
   - Links everything into `pglite.wasi`
   - Packages the result into `pglite-wasi.tar.xz`

## Upstream Dependencies

All tools are downloaded from their official upstream sources:

| Tool | Version | Source |
|------|---------|--------|
| wasi-sdk | 25.0 | [WebAssembly/wasi-sdk](https://github.com/WebAssembly/wasi-sdk) |
| wasmtime | 33.0.0 | [bytecodealliance/wasmtime](https://github.com/bytecodealliance/wasmtime) |
| wabt | 1.0.36 | [WebAssembly/wabt](https://github.com/WebAssembly/wabt) |
| zlib | 1.3.1 | [madler/zlib](https://github.com/madler/zlib) |

## CI

The GitHub Actions workflow (`.github/workflows/build.yml`) runs on every
push/PR to `main`. It builds the Docker image, runs the compilation, and
uploads the WASI binaries as artifacts.
