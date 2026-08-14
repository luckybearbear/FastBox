#!/usr/bin/env bash
# 编译打包 sha256 Java 插件（纯 JDK，无第三方依赖）
# 说明：不使用 rm -rf（部分环境拦截），增量覆盖编译
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out \
  src/com/fastbox/plugin/spi/FastBoxPlugin.java \
  src/com/fastbox/plugin/sha256/Sha256Plugin.java
jar cf sha256.jar -C out .
echo "OK -> sha256.jar"
