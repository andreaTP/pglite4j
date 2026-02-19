# Source tree must already be cloned and patched by entrypoint.sh
if [ ! -f postgresql-src/postgresql-src.patched ]; then
    echo "FATAL: postgresql-src not found or not patched. Run via entrypoint.sh."
    exit 1
fi

export PGSRC=$(realpath postgresql-src)

echo "

Building $ARCHIVE (patched) from $PGSRC WASI=$WASI


build-pgcore: begin($BUILD)
___________________________________________________

CC_PGLITE=$CC_PGLITE


"

if [ -f ${PGROOT}/pg.${BUILD}.installed ]
then
    echo "
    * skipping pg build, using previous install from ${PGROOT}
"
else

    mkdir -p ${BUILD_PATH}
    pushd ${BUILD_PATH}

    # create empty package.json to avoid node conflicts
    # with root package.json of project
    echo "{}" > package.json


    if [ -f Makefile ]
    then
        echo "Cleaning up previous build ..."
        make distclean 2>&1 > /dev/null
    fi


# TODO: --with-libxml    xml2 >= 2.6.23
# TODO: --with-libxslt   add to sdk
#  --disable-atomics https://github.com/WebAssembly/threads/pull/147  "Allow atomic operations on unshared memories"


    COMMON_CFLAGS="${CC_PGLITE} -fpic -Wno-declaration-after-statement -Wno-macro-redefined -Wno-unused-function -Wno-missing-prototypes -Wno-incompatible-pointer-types"

    # common to all wasm flavour
    cp ${PGSRC}/src/include/port/wasm_common.h ${PGROOT}/include/wasm_common.h

    # wasm os implementation router
    cp ${PORTABLE}/sdk_port.h ${PGROOT}/include/sdk_port.h

    # specific implementation for wasm os flavour
    [ -d  ${PORTABLE}/sdk_port-${BUILD} ] && cp ${PORTABLE}/sdk_port-${BUILD}/* ${PGROOT}/include/

    echo "WASI BUILD: turning off xml/xslt support"
    XML2=""
    UUID=""

    # WASI linker flags
    WASM_LDFLAGS="-lwasi-emulated-mman -lwasi-emulated-pthread -lwasi-emulated-process-clocks"
    WASM_CFLAGS="-I${WASISDK}/hotfix -DSDK_PORT=${PREFIX}/include/sdk_port-wasi.c ${COMMON_CFLAGS} -D_WASI_EMULATED_PTHREAD -D_WASI_EMULATED_MMAN -D_WASI_EMULATED_PROCESS_CLOCKS"
    export MAIN_MODULE=""

    export XML2_CONFIG=$PREFIX/bin/xml2-config

    if $USE_ICU
    then
        CNF_ICU="--with-icu"
    else
        CNF_ICU="--without-icu"
    fi


    CNF="${PGSRC}/configure --prefix=${PGROOT} --cache-file=${PGROOT}/config.cache.${BUILD} \
 --disable-spinlocks --disable-largefile --without-llvm \
 --without-pam --disable-largefile --with-openssl=no \
 --without-readline $CNF_ICU \
 ${UUID} ${XML2} ${PGDEBUG}"


    mkdir -p bin

    GETZIC=${GETZIC:-true}

    export EXT=wasi
    cat > ${PGROOT}/config.site <<END
ac_cv_exeext=.wasi
END
    if $GETZIC
    then
        cat > bin/zic <<END
#!/bin/bash
TZ=UTC PGTZ=UTC $(command -v wasi-run) $(pwd)/src/timezone/zic.wasi \$@
END
    else
        echo "
   * Using system ZIC from ${ZIC:-/usr/sbin/zic}
   "
        cp ${ZIC:-/usr/sbin/zic} bin/
    fi

    export ZIC=$(realpath bin/zic)


    if \
     EM_PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
     CONFIG_SITE="${PGROOT}/config.site" \
     CFLAGS="$WASM_CFLAGS" \
     LDFLAGS="$WASM_LDFLAGS" \
     wasi-configure $CNF --with-template=$BUILD
    then
        echo configure ok
    else
        echo configure failed
        exit 218
    fi

    echo "



    =============================================================

    building $BUILD wasm MVP:$MVP Debug=${DEBUG} with :

    opts : $@

    COPTS=$COPTS
    LOPTS=$LOPTS

    PYDK_CFLAGS=$PYDK_CFLAGS

    CFLAGS=$WASM_CFLAGS

    LDFLAGS=$WASM_LDFLAGS

    PG_DEBUG_HEADER=$PG_DEBUG_HEADER

    ZIC=$ZIC

    ===============================================================




    "


    if grep -q MAIN_MODULE ${PGSRC}/src/backend/Makefile
    then
        echo "dyld server patch ok"
    else
        echo "missing server dyld patch"
        exit 260
    fi

    # --disable-shared not supported so be able to use a fake linker

    > /tmp/disable-shared.log

    mkdir -p $PGROOT/bin

    cat > $PGROOT/bin/wasi-shared <<END
#!/bin/bash
echo "[\$(pwd)] $0 \$@" >> /tmp/disable-shared.log
# shared build
echo ===================================================================================
wasi-c -L${PREFIX}/lib -DPREFIX=${PGROOT} -shared \$@ -Wno-unused-function
echo ===================================================================================
END
    ln -sf $PGROOT/bin/wasi-shared bin/wasi-shared

    chmod +x bin/zic $PGROOT/bin/wasi-shared

    # for zic and wasi-shared called from makefile
    export PATH=$(pwd)/bin:$PATH

> /tmp/build.log
#  2>&1 > /tmp/build.log
    if $DEBUG
    then
        NCPU=1
    else
        NCPU=$(nproc)
    fi

    # Get rid of some build stages for now

    cat > src/test/Makefile <<END
# auto-edited for pglite
all: \$(echo src/test and src/test/isolation skipped)
clean check installcheck all-src-recurse: all
install: all
END
    cat src/test/Makefile > src/test/isolation/Makefile

    # Keep a shell script for fast rebuild with env -i from cmdline

    echo "#!/bin/bash
# /tmp/portable.opts

" > pg-make.sh
    cat /tmp/portable.opts  >> pg-make.sh
    cat >> pg-make.sh <<END
. ${SDKROOT}/wasisdk/wasisdk_env.sh
export PATH=$PGROOT/bin:\$PATH

# ZIC=$ZIC
# ${PGROOT}/pgopts.sh

END

    cat ${PGROOT}/pgopts.sh >> pg-make.sh

    cat >> pg-make.sh <<END

# linker stage
echo '

Linking ...

'

rm -vf libp*.a src/backend/postgres*

WASI_CFLAGS="${CC_PGLITE}" make AR=\${WASISDK}/upstream/bin/llvm-ar PORTNAME=$BUILD $BUILD=1 -j \${NCPU:-$NCPU} \$@

echo '____________________________________________________________'
du -hs src/port/libpgport_srv.a src/common/libpgcommon_srv.a libp*.a src/backend/postgres*
echo '____________________________________________________________'

END

    chmod +x pg-make.sh

    if env -i ./pg-make.sh install 2>&1 > /tmp/install.log
    then
        echo install ok
        cp src/backend/postgres.wasi $PGROOT/bin/ || exit 365

        pushd ${PGROOT}
            find . -type f | grep -v plpgsql > ${PGROOT}/pg.${BUILD}.installed
        popd

        pushd ${WORKSPACE}

            pushd ${PGROOT}
                find . -type f  > ${PGROOT}/pg.${BUILD}.installed
            popd

    else
        cat /tmp/install.log
        echo "install failed"
        exit 400
    fi

    python3 > ${PGROOT}/PGPASSFILE <<END
USER="${PGPASS:-postgres}"
PASS="${PGUSER:-postgres}"
md5pass =  "md5" + __import__('hashlib').md5(USER.encode() + PASS.encode()).hexdigest()
print(f"localhost:5432:postgres:{USER}:{md5pass}")

USER="postgres"
PASS="postgres"
md5pass =  "md5" + __import__('hashlib').md5(USER.encode() + PASS.encode()).hexdigest()
print(f"localhost:5432:postgres:{USER}:{md5pass}")

USER="login"
PASS="password"
md5pass =  "md5" + __import__('hashlib').md5(USER.encode() + PASS.encode()).hexdigest()
print(f"localhost:5432:postgres:{USER}:{md5pass}")
END

    # for extensions building
    chmod +x ${PGROOT}/bin/pg_config

	echo "TODO: node/wasi cmdline initdb for PGDATA=${PGDATA} "
    popd

fi

echo "build-pgcore: end($BUILD)




"
