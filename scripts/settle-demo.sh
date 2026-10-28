#!/usr/bin/env bash
# 把演示数据里的历史订单"逐笔结算"一遍，让金额与账单快照由计费引擎生成。
#
# 为什么需要这一步：seed_demo.sql 只灌 CHECKED_OUT、amount=0 的订单，
# 金额故意不手写 —— 手写的数字和 /bill/preview 算出来的对不上，
# 演示时点开历史账单会显示"实时试算"而不是"账单快照"，报表营收也经不起追问。
#
# 用法（后端需已启动）：
#   source scripts/env.sh
#   bash scripts/reset-demo.sh      # 重建库 + 灌数据
#   mvn spring-boot:run             # 另开一个终端起后端
#   bash scripts/settle-demo.sh     # 本脚本
set -euo pipefail
cd "$(dirname "$0")/.."

API="${API:-http://127.0.0.1:8080}"
USER_NAME="${DEMO_USER:-admin}"
PASSWORD="${DEMO_PASSWORD:-Admin@123}"
MYSQL_PWD="${MYSQL_PWD:-bnb_dev_123}"
JAR="$(mktemp)"

if ! curl -fsS -m 5 "$API/api/ping" > /dev/null 2>&1; then
  echo "后端没起来（$API/api/ping 不通）。先 mvn spring-boot:run 再跑本脚本。" >&2
  exit 1
fi

echo "==> 登录 $USER_NAME"
curl -fsS -c "$JAR" -X POST "$API/api/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" > /dev/null

# 直接问库要待结算的订单 id：比解析 JSON 可靠，而且这个脚本本来就依赖 Docker
IDS=$(docker compose exec -T mysql mysql -N -B -uroot -p"$MYSQL_PWD" bnb \
        -e "select id from booking where status = 'CHECKED_OUT' order by id" 2>/dev/null | tr -d '\r')

if [ -z "$IDS" ]; then
  echo "没有待结算的订单。若刚灌过种子数据，先确认 seed 里的历史单是 CHECKED_OUT 状态。"
  rm -f "$JAR"
  exit 0
fi

TOTAL=0
OK=0
FAIL=0
echo
printf '%-6s %-20s %10s  %s\n' 'id' '单号' '金额' '结果'
for id in $IDS; do
  RESP=$(curl -fsS -b "$JAR" -X POST "$API/api/bookings/$id/settle" || echo '{"code":-1}')
  if printf '%s' "$RESP" | grep -q '"code":0'; then
    AMOUNT=$(printf '%s' "$RESP" | grep -o '"totalAmount":[0-9.]*' | head -1 | cut -d: -f2)
    NO=$(printf '%s' "$RESP" | grep -o '"bookingNo":"[^"]*"' | head -1 | cut -d'"' -f4)
    printf '%-6s %-20s %10s  %s\n' "$id" "$NO" "${AMOUNT:-?}" '已结算'
    TOTAL=$(awk -v a="$TOTAL" -v b="${AMOUNT:-0}" 'BEGIN{printf "%.2f", a+b}')
    OK=$((OK + 1))
  else
    MSG=$(printf '%s' "$RESP" | grep -o '"message":"[^"]*"' | head -1 | cut -d'"' -f4)
    printf '%-6s %-20s %10s  %s\n' "$id" '-' '-' "失败：${MSG:-未知错误}"
    FAIL=$((FAIL + 1))
  fi
done

echo
echo "结算 $OK 笔，失败 $FAIL 笔，营收合计 $TOTAL 元"
echo "（金额全部由 PriceCalculator 算出并写回 booking.amount；bill 里存了当时的明细快照）"

rm -f "$JAR"
[ "$FAIL" -eq 0 ] || exit 1
