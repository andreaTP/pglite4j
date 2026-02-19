#!/bin/bash
export PG_VERSION=${PG_VERSION:-17.4}

#set -x;
#set -e;
export LC_ALL=C

export CI=${CI:-false}
export PORTABLE=${PORTABLE:-$(pwd)/wasm-build}
export SDKROOT=${SDKROOT:-/tmp/sdk}


# systems default may not be in path
export ZIC=${ZIC:-/usr/sbin/zic}

if [ -x $ZIC ]
then
    export GETZIC=false
else
    export GETZIC=true
fi

# data transfer zone this is == (wire query size + result size ) + 2
# expressed in MB, max is 13MB
export CMA_MB=${CMA_MB:-12}
export TOTAL_MEMORY=${TOTAL_MEMORY:-180MB}


export WORKSPACE=${GITHUB_WORKSPACE:-$(pwd)}
export PGROOT=${PGROOT:-/tmp/pglite}
export WEBROOT=${WEBROOT:-/tmp/web}

export PG_BUILD=${BUILD:-/tmp/sdk/build}
    export PG_BUILD_DUMPS=${PG_BUILD}/dumps
    export PGL_BUILD_NATIVE=${PG_BUILD}/pglite-native

export PG_DIST=${DIST:-/tmp/sdk/dist}

export DEBUG=${DEBUG:-true}

export USE_ICU=${USE_ICU:-false}
export PGUSER=${PGUSER:-postgres}

[ -f /tmp/portable.opts ] && . /tmp/portable.opts
[ -f /tmp/portable.dev ] && . /tmp/portable.dev

# WASI-only build
export WASI=true
export WASI_SDK=${WASI_SDK:-25.0}
export PYBUILD=${PYBUILD:-3.13}
export NATIVE=${NATIVE:-false}

BUILD=wasi
if $DEBUG
then
    export COPTS=${COPTS:-"-O2 -g3"}
    export LOPTS=${LOPTS:-"-O2 -g3"}
else
    export COPTS=${COPTS:-"-Oz -g0"}
    export LOPTS=${LOPTS:-"-Oz -g0"}
fi

export BUILD
export BUILD_PATH=${PG_BUILD}/${BUILD}

export PG_EXTRA=${PG_BUILD}/extra-${BUILD}


# default to user writeable paths in /tmp/ .
DIST_ALL="${PGROOT}/bin ${PG_DIST} ${PG_BUILD_DUMPS} ${PGL_BUILD_NATIVE}"
DIST_ALL="$DIST_ALL ${PG_EXTRA}"

if mkdir -p $DIST_ALL
then
    echo "checking for valid prefix ${PGROOT} ${PG_DIST}"
else
    sudo mkdir -p $DIST_ALL
    sudo chown $(whoami) -R $DIST_ALL
fi


export PGDATA=${PGROOT}/base


chmod +x ${PORTABLE}/*.sh
[ -d ${PORTABLE}/extra ] && ${PORTABLE}/extra/*.sh


# this was set to false on 16.4 to skip some harmless exceptions without messing with core code.
# exit on error
EOE=true


# TODO: also handle PGPASSFILE hostname:port:database:username:password
# https://www.postgresql.org/docs/devel/libpq-pgpass.html
export CRED="-U $PGUSER --pwfile=${PGROOT}/password"

if [ -f ${PGROOT}/password ]
then
    echo "not changing db password"
    PGPASS=$(cat ${PGROOT}/password)
else
    PGPASS=${PGPASS:-password}
    echo ${PGPASS:-password} > ${PGROOT}/password
fi

export PGPASS


export PG_DEBUG_HEADER="${PGROOT}/include/pg_debug.h"


# setup compiler
pushd ${SDKROOT}
    . wasisdk/wasisdk_env.sh
    echo "$PORTABLE : sdk check passed (wasi)"
popd

# used for not makefile (manual linking and pgl_main)
# pass the "kernel" contiguous memory zone size to the C compiler with CMA_MB which will be multiplied by 1024x1024 in
# preprocessed source.
export CC_PGLITE="-DPYDK=1 -DPG_PREFIX=${PGROOT} -I${PGROOT}/include -DCMA_MB=${CMA_MB}"

export WASI_CFLAGS="-D_WASI_EMULATED_PROCESS_CLOCKS -D_WASI_EMULATED_SIGNAL --target=wasm32-wasip1 -D__wasilibc_use_wasip1"
export CC_PGLITE="$WASI_CFLAGS $CC_PGLITE"


echo "
    ----------------------------------------
"
env|grep PG |grep -v BUILD
echo
env|grep BUILD|grep -v PG
echo
env|grep WA
echo
env|grep PY

echo "
    ----------------------------------------
PATH=${PATH}
    ----------------------------------------
"


# ========================= pg core configuration ============================

# testing postgres.js file instead of ${PGROOT}/pgopts.sh because build should not have failed.
if [ -f ${WEBROOT}/postgres.js ]
then
    echo using current from ${WEBROOT}

    . ${PGROOT}/pgopts.sh

else

    # default to web/release size optim.

    mkdir -p ${PGROOT}/include
    if $DEBUG
    then
        export PGDEBUG=""
        cat > ${PG_DEBUG_HEADER} << END
#ifndef I_PGDEBUG
#define I_PGDEBUG
#define WASM_USERNAME "$PGUSER"
#define PGDEBUG 1
#define PDEBUG(string) { fputs(string, stderr); fputs("\r\n", stderr); }
#define JSDEBUG(string) {EM_ASM({ console.log(string); });}
#define ADEBUG(string) { PDEBUG(string); JSDEBUG(string) }
#endif
END

    else
        export PGDEBUG=""
        cat > ${PG_DEBUG_HEADER} << END
#ifndef I_PGDEBUG
#define I_PGDEBUG
#define WASM_USERNAME "$PGUSER"
#define PDEBUG(string)
#define JSDEBUG(string)
#define ADEBUG(string)
#define PGDEBUG 0
#endif
END
    fi

    mkdir -p ${PGROOT}/include/postgresql/server
    for dest in ${PGROOT}/include ${PGROOT}/include/postgresql ${PGROOT}/include/postgresql/server
    do
        [ -f $dest/pg_debug.h ] || cp ${PG_DEBUG_HEADER} $dest/
    done

    # store all options that have impact on cmd line initdb/boot compile+link
    cat > ${PGROOT}/pgopts.sh <<END
export PG_BRANCH=$PG_BRANCH
export CMA_MB=$CMA_MB
export TOTAL_MEMORY=$TOTAL_MEMORY
export CC_PGLITE="$CC_PGLITE"

export CI=$CI
export PORTABLE=$PORTABLE
export SDKROOT=$SDKROOT
export GETZIC=$GETZIC
export ZIC=$ZIC
export WORKSPACE=$WORKSPACE
export PGROOT=$PGROOT
export WEBROOT=$WEBROOT
export PG_BUILD=$PG_BUILD
export PGL_BUILD_NATIVE=$PGL_BUILD_NATIVE
export PG_DIST=$PG_DIST

export DEBUG=$DEBUG

export USE_ICU=$USE_ICU
export PGUSER=$PGUSER

export WASI=$WASI
export WASI_SDK=$WASI_SDK
export PYBUILD=$PYBUILD

export BUILD_PATH=$BUILD_PATH
export COPTS="$COPTS"
export LOPTS="$LOPTS"
export PGDEBUG="$PGDEBUG"
export PG_DEBUG_HEADER=$PG_DEBUG_HEADER
export PGOPTS="\\
 -c log_checkpoints=false \\
 -c dynamic_shared_memory_type=posix \\
 -c search_path=pg_catalog \\
 -c exit_on_error=$EOE \\
 -c ignore_invalid_pages=on \\
 -c temp_buffers=8MB -c work_mem=4MB \\
 -c fsync=on -c synchronous_commit=on \\
 -c wal_buffers=4MB -c min_wal_size=80MB \\
 -c shared_buffers=128MB"

END

    export PGLITE=$(pwd)/packages/pglite

    echo "export PGSRC=${WORKSPACE}" >> ${PGROOT}/pgopts.sh
    echo "export PGLITE=${PGLITE}" >> ${PGROOT}/pgopts.sh

    [ -f /tmp/portable.opts ] && cat /tmp/portable.opts >> ${PGROOT}/pgopts.sh
    [ -f /tmp/portable.dev ] && cat /tmp/portable.dev >> ${PGROOT}/pgopts.sh

    . ${PGROOT}/pgopts.sh

    # make sure no non-mvp feature gets in.
    cat > ${PGROOT}/config.site <<END
pgac_cv_sse42_crc32_intrinsics_=no
pgac_cv_sse42_crc32_intrinsics__msse4_2=no
pgac_sse42_crc32_intrinsics=no
pgac_armv8_crc32c_intrinsics=no
ac_cv_search_sem_open=no

with_uuid=ossp
ac_cv_lib_ossp_uuid_uuid_export=yes
ac_cv_lib_uuid_uuid_generate=no
END


    # workaround no "locale -a" for Node.
    # this is simply the minimal result a popen call would give.
    mkdir -p ${PGROOT}/etc/postgresql
    cat > ${PGROOT}/etc/postgresql/locale <<END
C
C.UTF-8
POSIX
UTF-8
END

    . ${PORTABLE}/build-pgcore.sh
fi

# put local zic in the path from build dir
# put wasi-shared and also pg_config from the install dir.
export PATH=${WORKSPACE}/${BUILD_PATH}/bin:${PGROOT}/bin:${HOST_PREFIX}/bin:$PATH


# At this stage, PG should be installed to PREFIX and ready for linking
# or building ext.



# ===========================================================================
# ===========================================================================
#                             EXTENSIONS

cd ${WORKSPACE}
if ./wasm-build/build-ext.sh
then
    echo "
    contrib extensions built
    "
else
    echo "some contrib extensions failed to build"; exit $LINENO
fi

# ===========================================================================
# ===========================================================================

echo "



    ================================================================================
    ================================================================================



            $(md5sum /tmp/pglite/bin/pg_dump.wasi)



    ================================================================================
    ================================================================================



    "
cp /tmp/pglite/bin/pg_dump.wasi /tmp/sdk/dist/

# only build extra when targeting pglite-wasm .
rm -f pglite-link.sh

if [ -f ${WORKSPACE}/pglite-wasm/build.sh ]
then
    echo "
    * WASI build : TODO: FS building
    * WASI build : TODO: ext linking
"
    cat > pglite-link.sh <<END
. ${PGROOT}/pgopts.sh
. ${SDKROOT}/wasisdk/wasisdk_env.sh
if ./pglite-wasm/build.sh
then
    echo "TODO: tests"
fi
END

    chmod +x pglite-link.sh

    if ./pglite-link.sh
    then
        echo "TODO: extensions fs packing"
    fi

else
    echo "linking libpglite skipped"
fi

