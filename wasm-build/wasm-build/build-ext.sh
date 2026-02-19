#!/bin/bash

SKIP_CONTRIB=${SKIP_CONTRIB:-false}

cd ${WORKSPACE}


echo "

    * WASI: skipping some contrib extensions build

    "
    echo " ========= TODO WASI openssl ============== "
    SKIP="\
 [\
 sslinfo bool_plperl hstore_plperl hstore_plpython jsonb_plperl jsonb_plpython\
 ltree_plpython sepgsql bool_plperl start-scripts\
 pgcrypto uuid-ossp xml2\
 ]"
    SKIP_CONTRIB=true

# common wasi contrib build
if $SKIP_CONTRIB
then
    echo "
    * skipping contrib build
    "
    exit 0
fi

for extdir in postgresql-src/contrib/*
do

    if [ -d "$extdir" ]
    then
        ext=$(echo -n $extdir|cut -d/ -f3)
        if echo -n $SKIP|grep -q "$ext "
        then
            echo "

    skipping extension $ext

            "
        else
            echo "

    Building contrib extension : $ext : begin

            "
            pushd ${BUILD_PATH}/contrib/$ext
            if PATH=$PREFIX/bin:$PATH make install 2>&1 >/dev/null
            then
                echo "

    Building contrib extension : $ext : end

            "
            else
                echo "

    Extension $ext from $extdir failed to build

                "
                exit 69
            fi
            popd

        fi
    fi
done


if [ -f ${PG_BUILD_DUMPS}/dump.vector ]
then
    echo "

    *   NOT rebuilding extra extensions ( found ${PG_BUILD_DUMPS}/dump.vector )

"
else

    for extra_ext in ./extra/*.sh
    do
        echo "====  ${extra_ext} ===="
        ${extra_ext} || exit 112
    done


fi
