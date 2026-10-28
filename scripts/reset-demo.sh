#!/usr/bin/env bash
# 演示数据一键重置（实施计划第 6 节）
# 前提：docker compose up -d，且后端进程已停（本脚本会 drop database，
#       后端连着的话池里的连接会拿到已消失的 schema）
# 用法： source scripts/env.sh && bash scripts/reset-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# 检查 JAVA_HOME 而不是 java 命令：Git Bash 里含空格的 PATH 条目（"Program Files"）
# 会导致直接敲 java 找不到，但 Maven 用 JAVA_HOME 是好的 —— 别把这两件事混为一谈。
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java.exe" ]; then
  echo "JAVA_HOME 未指向可用 JDK：先执行  source scripts/env.sh  再重跑本脚本" >&2
  exit 1
fi

MYSQL_PWD=bnb_dev_123
MVN="${MVN:-E:/02_software/05_programming/04_IntelliJ_IDEA/plugins/maven/lib/maven3/bin/mvn}"

echo "==> 1/4 重建 schema（Flyway 历史记录随之清空）"
docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" \
  -e "drop database if exists bnb; create database bnb character set utf8mb4 collate utf8mb4_0900_ai_ci;"

echo "==> 2/4 跑迁移 V1 + V2（结构 + 结构性种子）"
sh "$MVN" -B -q flyway:migrate

echo "==> 3/4 校验迁移版本"
sh "$MVN" -B -q flyway:info

echo "==> 4/4 灌演示流水（不进 Flyway，所以可以反复重灌）"
# 必须显式指定 --default-character-set：mysql 客户端默认按 latin1 读文件，
# 而 seed_demo.sql 是 UTF-8 —— 不指定就会把每个中文字节再编码一次，
# 存进库变成"李文博"→9 个字符，前台显示成乱码（Flyway 走 JDBC 不受影响，所以只有这里是坏的）。
docker compose exec -T mysql mysql --default-character-set=utf8mb4 \
  -uroot -p"$MYSQL_PWD" bnb < db/demo/seed_demo.sql

echo
echo "完成。演示账号 admin / Staff 均为 Admin@123"
