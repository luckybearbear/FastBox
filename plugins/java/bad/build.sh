#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out \
  src/com/fastbox/plugin/spi/FastBoxPlugin.java \
  src/com/fastbox/plugin/bad/BadPlugin.java
jar cf bad.jar -C out .
echo "OK -> bad.jar"
