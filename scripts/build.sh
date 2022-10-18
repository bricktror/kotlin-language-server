#!/usr/bin/env bash

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}}")" &> /dev/null && pwd)
tag=kotlin-language-server-builder
cmd="${@:-./gradlew :server:installDist}"

podman build \
    -t $tag \
    -f ${SCRIPT_DIR}/../Dockerfile.build


podman run \
    --name $tag \
    --interactive \
    --tty \
    --rm \
    --volume ${SCRIPT_DIR}/../:/usr/src/kotlin-language-server \
    --workdir /usr/src/kotlin-language-server \
    $tag \
    sh -c "${cmd}"
