#!/bin/bash
set -e

# ── Configuration (can be overridden via docker run -e) ──────────────────────
export SDKROOT=/tmp/sdk
export PGROOT=/tmp/pglite
export DEBUG=${DEBUG:-true}
export USE_ICU=${USE_ICU:-false}
export PG_VERSION=${PG_VERSION:-17.5}
export PG_BRANCH=${PG_BRANCH:-REL_17_5_WASM-pglite}
export PGL_BRANCH=${PGL_BRANCH:-main}
export ZIC=${ZIC:-/usr/sbin/zic}
export GETZIC=${GETZIC:-false}
export WASI=true
export CI=${CI:-true}
export CMA_MB=${CMA_MB:-2}
export PGCRYPTO=${PGCRYPTO:-false}
export NATIVE=${NATIVE:-false}
export CONTAINER_PATH=""

export WORKSPACE=/workspace
export BUILD_PATH=${SDKROOT}/build/wasi
export PG_BUILD=${SDKROOT}/build
export PG_DIST=${SDKROOT}/dist

echo "
==============================================================================
  pglite WASI Docker Build
  PG_VERSION=${PG_VERSION}  PG_BRANCH=${PG_BRANCH}
  DEBUG=${DEBUG}
==============================================================================
"

# ── Source the WASI SDK environment ──────────────────────────────────────────
. ${SDKROOT}/wasisdk/wasisdk_env.sh

# ── Create portable.opts (read by wasm-build.sh) ────────────────────────────
cat > /tmp/portable.opts <<END
export PG_VERSION=${PG_VERSION}
export PG_BRANCH=${PG_BRANCH}
export CMA_MB=${CMA_MB}
export SDKROOT=${SDKROOT}
export DEBUG=${DEBUG}
export CI=${CI}
export WASI=${WASI}
export NATIVE=${NATIVE}
export PGROOT=${PGROOT}
export PGCRYPTO=${PGCRYPTO}
export USE_ICU=${USE_ICU}
export GETZIC=${GETZIC}
export ZIC=${ZIC}
END

cd ${WORKSPACE}

# ── Create expected directory names as symlinks ──────────────────────────────
# The build scripts reference patches-${PG_BRANCH}.
# Our repo uses a simpler name (patches/), so we symlink.
if [ ! -L "patches-${PG_BRANCH}" ] && [ ! -d "patches-${PG_BRANCH}" ]; then
    ln -sf patches "patches-${PG_BRANCH}"
fi

# ── Clone postgres-pglite if not present ─────────────────────────────────────
if [ ! -f "postgresql-${PG_BRANCH}/configure" ]; then
    echo "Cloning postgres-pglite branch ${PG_BRANCH}..."
    rm -rf "postgresql-${PG_BRANCH}"
    git clone --no-tags --depth 1 --single-branch --branch "${PG_BRANCH}" \
        https://github.com/electric-sql/postgres-pglite "postgresql-${PG_BRANCH}"
fi

# ── Check if already patched (by pre-patched fork) ──────────────────────────
if grep -q "PORTNAME.*emscripten" "postgresql-${PG_BRANCH}/contrib/pgcrypto/Makefile" 2>/dev/null; then
    touch "postgresql-${PG_BRANCH}/postgresql-${PG_BRANCH}.patched"
fi

# ── Apply patches if not already done ────────────────────────────────────────
cd "postgresql-${PG_BRANCH}"
if [ -f "postgresql-${PG_BRANCH}.patched" ]; then
    echo "Source tree already patched."
else
    echo "Applying patches..."

    # Create placeholder files that don't exist in vanilla PostgreSQL
    touch ./src/template/emscripten \
          ./src/include/port/emscripten.h \
          ./src/include/port/wasm_common.h \
          ./src/makefiles/Makefile.emscripten

    for patchdir in \
        postgresql-debug \
        postgresql-emscripten \
        postgresql-wasi \
        postgresql-pglite
    do
        if [ -d "${WORKSPACE}/patches-${PG_BRANCH}/${patchdir}" ]; then
            for one in "${WORKSPACE}/patches-${PG_BRANCH}/${patchdir}"/*.diff; do
                if patch -p1 < "$one"; then
                    echo "  Applied: $(basename $one)"
                else
                    echo "FATAL: failed to apply patch: $one"
                    exit 1
                fi
            done
        fi
    done
    touch "postgresql-${PG_BRANCH}.patched"
fi
cd ${WORKSPACE}

# ── Set up pglite-wasm directory from cloned repo ────────────────────────────
# The pglite-wasm/ files are part of the postgres-pglite fork (no need to vendor).
if [ ! -f pglite-wasm/build.sh ]; then
    mkdir -p pglite-wasm
    cp -Rv "postgresql-${PG_BRANCH}/pglite-wasm"/* pglite-wasm/
fi

# ── Fix upstream build.sh for wasi-sdk 25 compatibility ──────────────────────
# wasm-ld does not support --no-stack-first (it's the default; only --stack-first exists)
sed -i 's/-Wl,--no-stack-first //' pglite-wasm/build.sh

# ── Create pglite-${PG_BRANCH} symlink (needed by build scripts) ────────────
if [ ! -L "pglite-${PG_BRANCH}" ] && [ ! -d "pglite-${PG_BRANCH}" ]; then
    ln -sf pglite-wasm "pglite-${PG_BRANCH}"
fi

# ── Ensure PGROOT/bin exists and has wasm-objdump ────────────────────────────
# (bind-mounts shadow Dockerfile-created files under /tmp/pglite)
mkdir -p ${PGROOT}/bin ${PGROOT}/include
cp -f /usr/local/bin/wasm-objdump ${PGROOT}/bin/wasm-objdump 2>/dev/null || true

# ── Copy SDK port files to PREFIX/include (build-pgcore.sh expects them) ─────
cp -f wasm-build/sdk_port.h ${PREFIX}/include/sdk_port.h 2>/dev/null || true
cp -f wasm-build/sdk_port.h ${PGROOT}/include/sdk_port.h 2>/dev/null || true
if [ -d wasm-build/sdk_port-wasi ]; then
    cp -f wasm-build/sdk_port-wasi/* ${PREFIX}/include/ 2>/dev/null || true
    cp -f wasm-build/sdk_port-wasi/* ${PGROOT}/include/ 2>/dev/null || true
fi

# ── Create build/dist directories ───────────────────────────────────────────
mkdir -p "${BUILD_PATH}" "${PG_DIST}" "${SDKROOT}/build/dumps"

# ── Run the main build ──────────────────────────────────────────────────────
echo "
==============================================================================
  Starting wasm-build.sh
==============================================================================
"
exec ./wasm-build.sh contrib extra
