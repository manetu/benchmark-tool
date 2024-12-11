#!/bin/bash

set -eux -o pipefail

echo "Launching with JVM_OPTS:" $JVM_OPTS
exec java $JVM_OPTS -jar /usr/local/app.jar
