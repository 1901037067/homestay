#!/usr/bin/env bash
# 演示数据一键重置（实施计划第 6 节）
# 前提：docker compose up -d 且后端至少启动过一次（或者就用本脚本，它会自己建库）
# 用法： bash scripts/reset-demo.sh
set -euo pipefail
cd "$(dirname "$0")/.."

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
docker compose exec -T mysql mysql -uroot -p"$MYSQL_PWD" bnb < db/demo/seed_demo.sql

echo
echo "完成。演示账号 admin / Staff 均为 Admin@123"
