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

# ── Clone postgres-pglite if not present ─────────────────────────────────────
if [ ! -f "postgresql-src/configure" ]; then
    echo "Cloning postgres-pglite branch ${PG_BRANCH}..."
    rm -rf "postgresql-src"
    git clone --no-tags --depth 1 --single-branch --branch "${PG_BRANCH}" \
        https://github.com/electric-sql/postgres-pglite "postgresql-src"
fi

# ── Check if already patched (by pre-patched fork) ──────────────────────────
if grep -q "PORTNAME.*emscripten" "postgresql-src/contrib/pgcrypto/Makefile" 2>/dev/null; then
    touch "postgresql-src/postgresql-src.patched"
fi

# ── Apply patches if not already done ────────────────────────────────────────
cd "postgresql-src"
if [ -f "postgresql-src.patched" ]; then
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
        if [ -d "${WORKSPACE}/patches/${patchdir}" ]; then
            for one in "${WORKSPACE}/patches/${patchdir}"/*.diff; do
                if patch -p1 < "$one"; then
                    echo "  Applied: $(basename $one)"
                else
                    echo "FATAL: failed to apply patch: $one"
                    exit 1
                fi
            done
        fi
    done
    touch "postgresql-src.patched"
fi
# ── Always apply custom postgresql-pglite patches (our additions to the fork) ─
if [ ! -f "postgresql-pglite-custom.patched" ]; then
    if [ -d "${WORKSPACE}/patches/postgresql-pglite" ]; then
        echo "Applying custom postgresql-pglite patches..."
        for one in "${WORKSPACE}/patches/postgresql-pglite"/*.diff; do
            if patch --forward --dry-run -p1 < "$one" > /dev/null 2>&1; then
                if patch --forward -p1 < "$one"; then
                    echo "  Applied: $(basename $one)"
                else
                    echo "FATAL: failed to apply custom patch: $one"
                    exit 1
                fi
            else
                echo "  Skipped (already applied): $(basename $one)"
            fi
        done
    fi
    touch "postgresql-pglite-custom.patched"
fi
cd ${WORKSPACE}

# ── Set up pglite-wasm directory from cloned repo ────────────────────────────
# The pglite-wasm/ files are part of the postgres-pglite fork (no need to vendor).
if [ ! -f pglite-wasm/build.sh ]; then
    mkdir -p pglite-wasm
    cp -Rv "postgresql-src/pglite-wasm"/* pglite-wasm/
fi

# ── Apply pglite-wasm patches if not already done ────────────────────────────
cd pglite-wasm
if [ -f "pglite-wasm.patched" ]; then
    echo "pglite-wasm already patched."
else
    echo "Applying pglite-wasm patches..."
    if [ -d "${WORKSPACE}/patches/pglite-wasm" ]; then
        for one in "${WORKSPACE}/patches/pglite-wasm"/*.diff; do
            if patch -p1 < "$one"; then
                echo "  Applied: $(basename $one)"
            else
                echo "FATAL: failed to apply pglite-wasm patch: $one"
                exit 1
            fi
        done
    fi
    touch "pglite-wasm.patched"
fi
cd ${WORKSPACE}

# ── Symlink so upstream build.sh can find the source as postgresql-${PG_BRANCH} ──
ln -sf postgresql-src "postgresql-${PG_BRANCH}"

# ── Copy SDK port files to PREFIX/include (build-pgcore.sh expects them) ─────
cp -f wasm-build/sdk_port.h ${PREFIX}/include/sdk_port.h 2>/dev/null || true
cp -f wasm-build/sdk_port.h ${PGROOT}/include/sdk_port.h 2>/dev/null || true
if [ -d wasm-build/sdk_port-wasi ]; then
    cp -f wasm-build/sdk_port-wasi/* ${PREFIX}/include/ 2>/dev/null || true
    cp -f wasm-build/sdk_port-wasi/* ${PGROOT}/include/ 2>/dev/null || true
fi

# ── Ensure PGROOT/bin and include exist ──────────────────────────────────────
mkdir -p ${PGROOT}/bin ${PGROOT}/include

# ── Create build/dist directories ───────────────────────────────────────────
mkdir -p "${BUILD_PATH}" "${PG_DIST}" "${SDKROOT}/build/dumps"

# ── Run the main build ──────────────────────────────────────────────────────
echo "
==============================================================================
  Starting wasm-build.sh
==============================================================================
"
exec ./wasm-build.sh contrib extra
