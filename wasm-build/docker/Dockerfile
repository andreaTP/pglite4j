FROM debian:bookworm-slim

ARG WASI_SDK_VERSION=25.0
ARG ZLIB_VERSION=1.3.1
ARG BINARYEN_VERSION=125
ARG WASMTIME_VERSION=27.0.0
ARG WIZER_VERSION=10.0.0
ARG WASI_VFS_VERSION=0.6.2

ENV DEBIAN_FRONTEND=noninteractive
ENV SDKROOT=/tmp/sdk
ENV PGROOT=/tmp/pglite

# ── Install host build dependencies ──────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash git make gcc g++ \
    patch bison flex perl \
    python3 python3-dev \
    curl wget ca-certificates \
    tar xz-utils file liblz4-tool unzip \
    autoconf automake libtool pkg-config \
    findutils binutils coreutils \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# ── Create SDK directory structure ───────────────────────────────────────────
RUN mkdir -p \
    ${SDKROOT}/wasisdk/bin \
    ${SDKROOT}/wasisdk/hotfix \
    ${SDKROOT}/zlib/lib \
    ${SDKROOT}/zlib/include \
    ${SDKROOT}/build/wasi \
    ${SDKROOT}/dist \
    ${PGROOT}/bin \
    ${PGROOT}/include

# ── Download upstream wasi-sdk from WebAssembly/wasi-sdk ─────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz" && \
    tar xf wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz && \
    mv wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux ${SDKROOT}/wasisdk/upstream && \
    rm wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz

# ── Copy our SDK files (hotfix headers, sysfix overlays, wrapper scripts) ────
COPY sdk/ /tmp/sdk-files/

# ── Apply sysfix overlays to the upstream sysroot ────────────────────────────
# These replace/add headers in the wasi-sysroot for POSIX compatibility.
# Apply to both wasm32-wasi and wasm32-wasip1 (compiler uses --target=wasm32-wasip1).
RUN for target in wasm32-wasi wasm32-wasip1; do \
        SYSROOT=${SDKROOT}/wasisdk/upstream/share/wasi-sysroot/include/${target} && \
        cp -f /tmp/sdk-files/sysfix/bits/alltypes.h  ${SYSROOT}/bits/alltypes.h && \
        cp -f /tmp/sdk-files/sysfix/bits/shm.h       ${SYSROOT}/bits/shm.h && \
        cp -f /tmp/sdk-files/sysfix/setjmp.h         ${SYSROOT}/setjmp.h && \
        cp -f /tmp/sdk-files/sysfix/stdio.h          ${SYSROOT}/stdio.h && \
        cp -f /tmp/sdk-files/sysfix/__struct_sockaddr_un.h ${SYSROOT}/__struct_sockaddr_un.h && \
        cp -f /tmp/sdk-files/sysfix/__header_sys_resource.h ${SYSROOT}/__header_sys_resource.h && \
        mkdir -p ${SYSROOT}/sys && \
        cp -f /tmp/sdk-files/sysfix/sys/mman.h       ${SYSROOT}/sys/mman.h && \
        cp -f /tmp/sdk-files/sysfix/sys/shm.h        ${SYSROOT}/sys/shm.h; \
    done

# ── Install hotfix headers ──────────────────────────────────────────────────
RUN cp -r /tmp/sdk-files/hotfix/* ${SDKROOT}/wasisdk/hotfix/

# ── Install wrapper scripts ─────────────────────────────────────────────────
RUN cp -r /tmp/sdk-files/bin/* ${SDKROOT}/wasisdk/bin/ && \
    cp ${SDKROOT}/wasisdk/bin/wasisdk_env.sh ${SDKROOT}/wasisdk/wasisdk_env.sh && \
    chmod +x ${SDKROOT}/wasisdk/bin/* ${SDKROOT}/wasisdk/wasisdk_env.sh && \
    rm -rf /tmp/sdk-files

# ── Set up PATH so wasi-c, clang etc. are available ──────────────────────────
ENV PATH="${SDKROOT}/wasisdk/bin:${SDKROOT}/wasisdk/upstream/bin:${PGROOT}/bin:/usr/local/bin:${PATH}"

# ── Create minimal SDK config file ──────────────────────────────────────────
RUN printf '#!/bin/bash\nexport SYS_PYTHON=${SYS_PYTHON:-$(which python3)}\n' \
    > ${SDKROOT}/config

# ── Cross-compile zlib for WASI ─────────────────────────────────────────────
# The build needs libz.a for PostgreSQL compression support.
# Use clang directly (not wasi-c wrapper) since zlib is simple C code
# and doesn't need the PostgreSQL-specific stubs from patch.h.
RUN cd /tmp && \
    wget -q "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" && \
    tar xf zlib-${ZLIB_VERSION}.tar.gz && \
    cd zlib-${ZLIB_VERSION} && \
    CHOST=wasm32-wasi \
    CC="${SDKROOT}/wasisdk/upstream/bin/clang" \
    AR="${SDKROOT}/wasisdk/upstream/bin/llvm-ar" \
    RANLIB="${SDKROOT}/wasisdk/upstream/bin/ranlib" \
    CFLAGS="--target=wasm32-wasip1 --sysroot=${SDKROOT}/wasisdk/upstream/share/wasi-sysroot -O2 -fPIC" \
    LDFLAGS="--target=wasm32-wasip1 --sysroot=${SDKROOT}/wasisdk/upstream/share/wasi-sysroot" \
    ./configure --static --prefix=${SDKROOT}/zlib && \
    make -j$(nproc) && \
    make install && \
    cd /tmp && rm -rf zlib-${ZLIB_VERSION} zlib-${ZLIB_VERSION}.tar.gz

# ── Create stub libxml2.a (XML is disabled but -lxml2 appears in link flags) ─
RUN ${SDKROOT}/wasisdk/upstream/bin/llvm-ar rcs ${SDKROOT}/zlib/lib/libxml2.a

# ── Download binaryen (wasm-opt) ──────────────────────────────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz" && \
    tar xzf binaryen-version_${BINARYEN_VERSION}-x86_64-linux.tar.gz && \
    mv binaryen-version_${BINARYEN_VERSION}/bin/wasm-opt /usr/local/bin/wasm-opt && \
    chmod +x /usr/local/bin/wasm-opt && \
    rm -rf binaryen-version_${BINARYEN_VERSION}*

# ── Download wasmtime (WASI runtime for pre-initializing pgdata) ──────────────
RUN cd /tmp && \
    wget -q "https://github.com/bytecodealliance/wasmtime/releases/download/v${WASMTIME_VERSION}/wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz" && \
    tar xJf wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz && \
    mv wasmtime-v${WASMTIME_VERSION}-x86_64-linux/wasmtime /usr/local/bin/wasmtime && \
    chmod +x /usr/local/bin/wasmtime && \
    rm -rf wasmtime-v${WASMTIME_VERSION}-x86_64-linux*

# ── Download wizer (pre-initializer for WASM) ────────────────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/bytecodealliance/wizer/releases/download/v${WIZER_VERSION}/wizer-v${WIZER_VERSION}-x86_64-linux.tar.xz" && \
    tar xJf wizer-v${WIZER_VERSION}-x86_64-linux.tar.xz && \
    mv wizer-v${WIZER_VERSION}-x86_64-linux/wizer /usr/local/bin/wizer && \
    chmod +x /usr/local/bin/wizer && \
    rm -rf wizer-v${WIZER_VERSION}-x86_64-linux*

# ── Download wasi-vfs (embed files into WASM binary) ─────────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/kateinoigakukun/wasi-vfs/releases/download/v${WASI_VFS_VERSION}/libwasi_vfs-wasm32-unknown-unknown.zip" && \
    unzip -o libwasi_vfs-wasm32-unknown-unknown.zip -d /tmp/wasi-vfs-lib && \
    cp /tmp/wasi-vfs-lib/libwasi_vfs.a ${SDKROOT}/wasisdk/upstream/share/wasi-sysroot/lib/wasm32-wasip1/ && \
    rm -rf libwasi_vfs-wasm32-unknown-unknown.zip /tmp/wasi-vfs-lib && \
    wget -q "https://github.com/kateinoigakukun/wasi-vfs/releases/download/v${WASI_VFS_VERSION}/wasi-vfs-cli-x86_64-unknown-linux-gnu.zip" && \
    unzip -o wasi-vfs-cli-x86_64-unknown-linux-gnu.zip -d /tmp/wasi-vfs-cli && \
    mv /tmp/wasi-vfs-cli/wasi-vfs /usr/local/bin/wasi-vfs && \
    chmod +x /usr/local/bin/wasi-vfs && \
    rm -rf wasi-vfs-cli-x86_64-unknown-linux-gnu.zip /tmp/wasi-vfs-cli

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /workspace
ENTRYPOINT ["/entrypoint.sh"]
