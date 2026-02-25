#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_NAME="pglite-wasi-builder"

# Build outputs go here (on host, as a subfolder)
OUTPUT_DIR="${SCRIPT_DIR}/output"
mkdir -p "${OUTPUT_DIR}"

# Copy SDK files into docker build context
rm -rf "${SCRIPT_DIR}/docker/sdk"
cp -r "${SCRIPT_DIR}/sdk" "${SCRIPT_DIR}/docker/sdk"

echo "=== Building Docker image ==="
docker build -t "${IMAGE_NAME}" "${SCRIPT_DIR}/docker"

# Clean up SDK copy from docker context
rm -rf "${SCRIPT_DIR}/docker/sdk"

# Clean previous build if requested
if [ "${CLEAN:-false}" = "true" ]; then
    echo "=== Cleaning previous build ==="
    docker run --rm -v "${OUTPUT_DIR}:/data" alpine:3.21 rm -rf /data/sdk-build /data/sdk-dist /data/pglite /data/pgdata
fi

# Always re-apply patches by removing sentinel files and temporary patched dirs.
# These are root-owned (created inside docker), so use docker to clean them.
docker run --rm -v "${SCRIPT_DIR}:/workspace:rw" alpine:3.21 sh -c \
    "rm -rf /workspace/pglite-wasm && rm -f /workspace/postgresql-src/postgresql-src.patched && rm -f /workspace/postgresql-src/postgresql-pglite-custom.patched"

mkdir -p "${OUTPUT_DIR}/sdk-build" "${OUTPUT_DIR}/sdk-dist" "${OUTPUT_DIR}/pglite" "${OUTPUT_DIR}/pgdata"

echo "=== Running WASI build ==="
docker run --rm \
    -v "${SCRIPT_DIR}:/workspace:rw" \
    -v "${OUTPUT_DIR}/sdk-build:/tmp/sdk/build:rw" \
    -v "${OUTPUT_DIR}/sdk-dist:/tmp/sdk/dist:rw" \
    -v "${OUTPUT_DIR}/pglite:/tmp/pglite:rw" \
    -v "${OUTPUT_DIR}/pgdata:/pgdata:rw" \
    -e DEBUG="${DEBUG:-true}" \
    -e PG_VERSION="${PG_VERSION:-17.5}" \
    -e PG_BRANCH="${PG_BRANCH:-REL_17_5_WASM-pglite}" \
    -e CI="${CI:-true}" \
    -e WASM_OPT_FLAGS="${WASM_OPT_FLAGS:--Oz --strip-debug}" \
    "${IMAGE_NAME}"

echo "
=== Build complete ===
Outputs:
  ${OUTPUT_DIR}/sdk-dist/pglite.wasi         - pglite WASI binary (23 MB)
  ${OUTPUT_DIR}/sdk-dist/pg_dump.wasi        - pg_dump WASI binary
  ${OUTPUT_DIR}/sdk-dist/pglite-wasi.tar.xz  - distribution archive
  ${OUTPUT_DIR}/pglite/                      - full PostgreSQL WASI installation
"
