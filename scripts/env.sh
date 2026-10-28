# 本机开发环境（只影响当前 shell，不改系统配置）
# 用法： source scripts/env.sh
#
# 说明：系统级 JAVA_HOME 已经是 Temurin 17（MSI 装的），新开终端本来就对。
# 这个脚本是给"继承了旧环境变量"的终端用的 —— 比如 IDE/agent 内嵌的 Git Bash，
# 里面 JAVA_HOME 可能还指着已失效的 C:\Program Files\Java\latest\jdk-26（空壳目录，
# java -version 会静默无输出，极易误判成"没装 Java"）。
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
# Git Bash 不认含空格的 Windows 形式 PATH 条目，必须转成 /c/Program Files/... 才能直接敲 java
_JAVA_BIN="$(cygpath -u "$JAVA_HOME")/bin"
export PATH="$_JAVA_BIN:$PATH"

# Maven 不单独装：用 IDEA 内置的 3.9.11。镜像与本地仓库由 ~/.m2/settings.xml 决定
# （阿里云 mirrorOf=central，localRepository 在 E:/02_software/05_programming/maven_repository）
if [ -z "${MVN:-}" ]; then
  MVN_HOME="${MVN_HOME:-E:/02_software/05_programming/04_IntelliJ_IDEA/plugins/maven/lib/maven3}"
  mvn() { sh "$MVN_HOME/bin/mvn" "$@"; }
fi

echo "JAVA_HOME=$JAVA_HOME"
java -version 2>&1 | head -1
