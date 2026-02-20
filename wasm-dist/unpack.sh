#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCES_DIR="${SCRIPT_DIR}/../core/src/main/resources"
ARCHIVE="${SCRIPT_DIR}/../wasm-build/output/sdk-dist/pglite-wasi.tar.xz"

if [ ! -f "${ARCHIVE}" ]; then
    echo "Error: Archive not found at ${ARCHIVE}"
    echo "Run the WASM build first: cd ../wasm-build && ./build.sh"
    exit 1
fi

echo "=== Unpacking pglite-wasi.tar.xz into ${SCRIPT_DIR} ==="

# Clean previous unpack
rm -rf "${SCRIPT_DIR}/tmp"
rm -rf "${RESOURCES_DIR}/pglite"
rm -f "${RESOURCES_DIR}/pglite-files.txt"

# Extract
tar -xJf "${ARCHIVE}" -C "${SCRIPT_DIR}"

echo "=== Copying pglite.wasi to top-level ==="
cp "${SCRIPT_DIR}/tmp/pglite/bin/pglite.wasi" "${SCRIPT_DIR}/pglite.wasi"

echo "=== Copying distribution files to core/src/main/resources/pglite/ ==="
mkdir -p "${RESOURCES_DIR}"
cp -r "${SCRIPT_DIR}/tmp/pglite" "${RESOURCES_DIR}/pglite"
rm -f "${RESOURCES_DIR}/pglite/bin/pglite.wasi"

echo "=== Generating pglite-files.txt manifest ==="
cd "${RESOURCES_DIR}"
find pglite -type f | sort > "${RESOURCES_DIR}/pglite-files.txt"

echo "=== Done ==="
echo "Artifacts:"
ls -lh "${SCRIPT_DIR}/pglite.wasi"
wc -l "${RESOURCES_DIR}/pglite-files.txt"
