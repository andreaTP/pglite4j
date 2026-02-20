#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARCHIVE="${SCRIPT_DIR}/../wasm-build/output/sdk-dist/pglite-wasi.tar.xz"

if [ ! -f "${ARCHIVE}" ]; then
    echo "Error: Archive not found at ${ARCHIVE}"
    echo "Run the WASM build first: cd ../wasm-build && ./build.sh"
    exit 1
fi

echo "=== Unpacking pglite-wasi.tar.xz into ${SCRIPT_DIR} ==="

# Clean previous unpack
rm -rf "${SCRIPT_DIR}/tmp"

# Extract
tar -xJf "${ARCHIVE}" -C "${SCRIPT_DIR}"

echo "=== Done ==="
echo "Contents:"
ls -la "${SCRIPT_DIR}/tmp/pglite/bin/"
