#!/bin/bash
# WASI SDK environment setup.
# Sources this to configure CC, CXX, AR, etc. for WASI cross-compilation.

if [ -z "${WASISDK_ENV+z}" ]; then
    SDKROOT=${SDKROOT:-/tmp/sdk}
    . ${CONFIG:-${SDKROOT}/config}

    export WASISDK_ENV=true

    export WASI=true
    export ARCH=wasisdk
    export WASISDK=${WASISDK:-"${SDKROOT}/${ARCH}"}
    export WASI_SDK_PREFIX="${WASISDK}/upstream"
    export WASI_SDK_DIR=$WASI_SDK_PREFIX
    export WASI_SYSROOT="${WASI_SDK_PREFIX}/share/wasi-sysroot"

    export CMAKE_TOOLCHAIN_FILE=${WASISDK}/share/cmake/Modules/Platform/WASI.cmake

    export CMAKE_INSTALL_PREFIX="${SDKROOT}/devices/${ARCH}/usr"
    export PREFIX=$CMAKE_INSTALL_PREFIX

    if [ -d ${WASI_SDK_PREFIX}/bin ]; then
        echo "
        * using wasisdk from $(realpath ${SDKROOT}/wasisdk/upstream),
        * with sys python $SYS_PYTHON
        * host python $HPY

" 1>&2
    else
        echo "${WASI_SDK_PREFIX}/bin not found" 1>&2
        exit 1
    fi

    export PATH="${WASISDK}/bin:${WASI_SDK_PREFIX}/bin:$PATH"

    # instruct pkg-config to use wasi target root
    export PKG_CONFIG_LIBDIR="${WASI_SYSROOT}/lib/wasm32-wasi/pkgconfig"
    export PKG_CONFIG_SYSROOT_DIR="${WASI_SYSROOT}"
    export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${WASI_SYSROOT}/lib/pkgconfig"

    export LDSHARED="${WASI_SDK_PREFIX}/bin/wasm-ld"
    export AR="${WASI_SDK_PREFIX}/bin/llvm-ar"
    export RANLIB="${WASI_SDK_PREFIX}/bin/ranlib"

    export CC="${WASISDK}/bin/wasi-c"
    export WASI_CC="${WASISDK}/bin/wasi-c"
    export CPP="${WASISDK}/bin/wasi-cpp"
    export CXX="${WASISDK}/bin/wasi-c++"

else
    echo "wasidk: config already set!" 1>&2
fi
