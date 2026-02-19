FROM debian:bookworm-slim

ARG WASI_SDK_VERSION=25.0
ARG WASMTIME_VERSION=33.0.0
ARG WABT_VERSION=1.0.36
ARG ZLIB_VERSION=1.3.1

ENV DEBIAN_FRONTEND=noninteractive
ENV SDKROOT=/tmp/sdk
ENV PGROOT=/tmp/pglite

# ── Install host build dependencies ──────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash git make gcc g++ \
    patch bison flex perl \
    python3 python3-dev \
    curl wget ca-certificates \
    tar xz-utils file liblz4-tool \
    nodejs \
    autoconf automake libtool pkg-config \
    findutils binutils coreutils \
    tzdata \
    && rm -rf /var/lib/apt/lists/*

# ── Create SDK directory structure ───────────────────────────────────────────
RUN mkdir -p \
    ${SDKROOT}/wasisdk/bin \
    ${SDKROOT}/wasisdk/hotfix \
    ${SDKROOT}/devices/x86_64/usr/bin \
    ${SDKROOT}/devices/wasisdk/usr/lib \
    ${SDKROOT}/devices/wasisdk/usr/include \
    ${SDKROOT}/build/wasi \
    ${SDKROOT}/dist \
    ${SDKROOT}/emsdk/node/20.18.0_64bit/bin \
    ${PGROOT}/bin \
    ${PGROOT}/include

# ── Download upstream wasi-sdk from WebAssembly/wasi-sdk ─────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz" && \
    tar xf wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz && \
    mv wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux ${SDKROOT}/wasisdk/upstream && \
    rm wasi-sdk-${WASI_SDK_VERSION}-x86_64-linux.tar.gz

# ── Install wasmtime from upstream bytecodealliance ──────────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/bytecodealliance/wasmtime/releases/download/v${WASMTIME_VERSION}/wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz" && \
    tar xf wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz && \
    cp wasmtime-v${WASMTIME_VERSION}-x86_64-linux/wasmtime ${SDKROOT}/devices/x86_64/usr/bin/ && \
    ln -s ${SDKROOT}/devices/x86_64/usr/bin/wasmtime /usr/local/bin/wasmtime && \
    rm -rf wasmtime-v${WASMTIME_VERSION}-x86_64-linux wasmtime-v${WASMTIME_VERSION}-x86_64-linux.tar.xz

# ── Install wasm-objdump from WABT ──────────────────────────────────────────
RUN cd /tmp && \
    wget -q "https://github.com/WebAssembly/wabt/releases/download/${WABT_VERSION}/wabt-${WABT_VERSION}-ubuntu-20.04.tar.gz" && \
    tar xf wabt-${WABT_VERSION}-ubuntu-20.04.tar.gz && \
    cp wabt-${WABT_VERSION}/bin/wasm-objdump /usr/local/bin/wasm-objdump && \
    rm -rf wabt-${WABT_VERSION} wabt-${WABT_VERSION}-ubuntu-20.04.tar.gz

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

# ── Set up PATH so wasi-c, clang, wasmtime etc. are available ───────────────
ENV PATH="${SDKROOT}/wasisdk/bin:${SDKROOT}/wasisdk/upstream/bin:${SDKROOT}/devices/x86_64/usr/bin:${PGROOT}/bin:/usr/local/bin:${PATH}"

# ── Create minimal SDK config file ──────────────────────────────────────────
RUN printf '#!/bin/bash\nexport SYS_PYTHON=${SYS_PYTHON:-$(which python3)}\n' \
    > ${SDKROOT}/config

# ── Create wasm32-wasi-shell.sh (environment bootstrap for env -i contexts) ─
# This script is sourced by pg-make.sh which runs under env -i (empty env),
# so it must establish the full PATH including system tools.
RUN echo '#!/bin/bash' > ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo 'export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo 'export SDKROOT="${SDKROOT:-/tmp/sdk}"' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo 'export PGROOT="${PGROOT:-/tmp/pglite}"' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo '. ${SDKROOT}/config' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo '. ${SDKROOT}/wasisdk/wasisdk_env.sh' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    echo 'export PATH="${PGROOT}/bin:${PATH}"' >> ${SDKROOT}/wasm32-wasi-shell.sh && \
    chmod +x ${SDKROOT}/wasm32-wasi-shell.sh

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
    ./configure --static --prefix=${SDKROOT}/devices/wasisdk/usr && \
    make -j$(nproc) && \
    make install && \
    cd /tmp && rm -rf zlib-${ZLIB_VERSION} zlib-${ZLIB_VERSION}.tar.gz

# ── Create stub libxml2.a (XML is disabled but -lxml2 appears in link flags) ─
RUN ${SDKROOT}/wasisdk/upstream/bin/llvm-ar rcs ${SDKROOT}/devices/wasisdk/usr/lib/libxml2.a

# ── Symlink node into expected emsdk path ────────────────────────────────────
RUN ln -sf $(which node) ${SDKROOT}/emsdk/node/20.18.0_64bit/bin/node

# ── Symlink python3 into host tools path ─────────────────────────────────────
RUN ln -sf $(which python3) ${SDKROOT}/devices/x86_64/usr/bin/python3

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

WORKDIR /workspace
ENTRYPOINT ["/entrypoint.sh"]
