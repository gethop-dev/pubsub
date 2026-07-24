#!/usr/bin/env bash

set -eu -o pipefail

# Tell docker-compose what compose files to use to run the different commands below
# Order of the files IS IMPORTANT, as later files overwrite values from previous ones.
# See https://docs.docker.com/compose/reference/envvars/#compose_file
export COMPOSE_FILE="docker-compose.yml"

# Stop any containers still running. Don't wait for them to finish :-)
docker/docker-compose.sh down --timeout 0
