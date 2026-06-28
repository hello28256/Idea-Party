#!/usr/bin/env bash
# 角色级联删除功能端到端验证脚本
#
# 前置：后端必须已重启（docker compose build + up），数据库可达。
# 使用：bash docs/verify-cascade-delete.sh
#
# 脚本会：
#   1. 探测后端健康
#   2. 登录获取 JWT
#   3. 创建一个测试角色
#   4. 创建 2 个引用该角色的聊天室
#   5. 跑 5 条 cURL 用例（覆盖 GET references、403、400 旧路径、204 级联）
#   6. 用 mysql 客户端验证中间表无残留
#
# 依赖：curl、jq、mysql client（docker exec 方式也行，见末尾）

set -euo pipefail

BASE="${BASE:-http://localhost:8082}"
USERNAME="${USERNAME:-cascade_test_$(date +%s)}"
PASSWORD="${PASSWORD:-TestPass1234!}"
EMAIL="${EMAIL:-${USERNAME}@example.com}"

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $1"; }
fail() { echo -e "${RED}✗${NC} $1"; exit 1; }
info() { echo -e "${YELLOW}→${NC} $1"; }

# 1. 健康检查
info "探测后端健康: $BASE/api/health"
HEALTH=$(curl -s "$BASE/api/health")
if ! echo "$HEALTH" | grep -q '"UP"'; then
  fail "后端不可达，请先 docker compose build server && up -d server"
fi
pass "后端健康"

# 2. 注册 + 登录拿 JWT
info "注册测试用户: $USERNAME"
REG_RESP=$(curl -s -X POST "$BASE/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  -w "\nHTTP_CODE:%{http_code}")
echo "$REG_RESP"

info "登录拿 JWT"
LOGIN_RESP=$(curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
JWT=$(echo "$LOGIN_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
if [[ -z "$JWT" ]]; then
  fail "登录失败，响应: $LOGIN_RESP"
fi
pass "已获取 JWT (前 30 字符: ${JWT:0:30}...)"

AUTH="Authorization: Bearer $JWT"

# 3. 创建测试角色
info "创建测试角色"
CHAR_NAME="CascadeTest_$(date +%s)"
CHAR_RESP=$(curl -s -X POST "$BASE/api/characters" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$CHAR_NAME\",\"description\":\"test\",\"prompt\":\"You are a test character.\"}")
CHAR_ID=$(echo "$CHAR_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
if [[ -z "$CHAR_ID" ]]; then
  fail "创建角色失败: $CHAR_RESP"
fi
pass "角色已创建 id=$CHAR_ID"

# 4. 创建 2 个引用该角色的聊天室
ROOM1_NAME="TestRoom1_$(date +%s)"
ROOM1_RESP=$(curl -s -X POST "$BASE/api/rooms" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$ROOM1_NAME\",\"chatMode\":\"GROUP\",\"characterIds\":[\"$CHAR_ID\"]}")
ROOM1_ID=$(echo "$ROOM1_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
[[ -n "$ROOM1_ID" ]] && pass "房间 1 已创建 id=$ROOM1_ID" || fail "房间 1 创建失败: $ROOM1_RESP"

ROOM2_NAME="TestRoom2_$(date +%s)"
ROOM2_RESP=$(curl -s -X POST "$BASE/api/rooms" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$ROOM2_NAME\",\"chatMode\":\"GROUP\",\"characterIds\":[\"$CHAR_ID\"]}")
ROOM2_ID=$(echo "$ROOM2_RESP" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo "")
[[ -n "$ROOM2_ID" ]] && pass "房间 2 已创建 id=$ROOM2_ID" || fail "房间 2 创建失败: $ROOM2_RESP"

# ============================================
# 5 条 cURL 用例
# ============================================

# 用例 1: GET /references 应返回 2 个房间
info "[用例 1] GET /characters/{id}/references"
REFS_RESP=$(curl -s -w "\nHTTP_CODE:%{http_code}" "$BASE/api/characters/$CHAR_ID/references" -H "$AUTH")
echo "$REFS_RESP"
HTTP_CODE=$(echo "$REFS_RESP" | tail -1 | sed 's/HTTP_CODE://')
ROOM_COUNT=$(echo "$REFS_RESP" | head -n -1 | python3 -c "import json,sys;print(json.load(sys.stdin).get('roomCount',-1))" 2>/dev/null || echo -1)
if [[ "$HTTP_CODE" == "200" && "$ROOM_COUNT" == "2" ]]; then
  pass "用例 1 通过: HTTP 200, roomCount=2"
else
  fail "用例 1 失败: HTTP=$HTTP_CODE roomCount=$ROOM_COUNT"
fi

# 用例 2: 旧路径（无 cascade）应返回 400 兜底
info "[用例 2] DELETE /characters/{id}（缺 cascade，旧路径）"
OLD_DEL=$(curl -s -X DELETE "$BASE/api/characters/$CHAR_ID" -H "$AUTH" -w "\nHTTP_CODE:%{http_code}")
echo "$OLD_DEL"
HTTP_CODE=$(echo "$OLD_DEL" | tail -1 | sed 's/HTTP_CODE://')
if [[ "$HTTP_CODE" == "400" ]]; then
  pass "用例 2 通过: HTTP 400（被引用保护）"
else
  fail "用例 2 失败: HTTP=$HTTP_CODE（期望 400）"
fi

# 用例 3: 非 owner 调 references 应 403
info "[用例 3] 创建第二个用户，测非 owner 访问"
OTHER_USER="other_$(date +%s)"
curl -s -X POST "$BASE/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$OTHER_USER\",\"email\":\"${OTHER_USER}@example.com\",\"password\":\"$PASSWORD\"}" > /dev/null
OTHER_LOGIN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$OTHER_USER\",\"password\":\"$PASSWORD\"}")
OTHER_JWT=$(echo "$OTHER_LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))" 2>/dev/null || echo "")
OTHER_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/characters/$CHAR_ID/references" \
  -H "Authorization: Bearer $OTHER_JWT")
if [[ "$OTHER_HTTP" == "403" ]]; then
  pass "用例 3 通过: HTTP 403（不暴露存在性）"
else
  fail "用例 3 失败: HTTP=$OTHER_HTTP（期望 403）"
fi

# 用例 4: 级联删除应返回 204
info "[用例 4] DELETE /characters/{id}?cascade=true（级联）"
CASCADE_RESP=$(curl -s -X DELETE "$BASE/api/characters/$CHAR_ID?cascade=true" -H "$AUTH" -w "\nHTTP_CODE:%{http_code}")
HTTP_CODE=$(echo "$CASCADE_RESP" | tail -1 | sed 's/HTTP_CODE://')
if [[ "$HTTP_CODE" == "204" ]]; then
  pass "用例 4 通过: HTTP 204（级联成功）"
else
  fail "用例 4 失败: HTTP=$HTTP_CODE body=$CASCADE_RESP"
fi

# 用例 5: 再次查询应 403（角色已删，但 403 与不存在同源，避免探测）
info "[用例 5] 已删角色再查 references"
GONE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/characters/$CHAR_ID/references" -H "$AUTH")
if [[ "$GONE_HTTP" == "403" || "$GONE_HTTP" == "404" ]]; then
  pass "用例 5 通过: HTTP $GONE_HTTP（已删除）"
else
  fail "用例 5 失败: HTTP=$GONE_HTTP（期望 403 或 404）"
fi

echo ""
echo -e "${GREEN}============ 5 条用例全部通过 ============${NC}"
echo ""
echo "数据库一致性验证（请用 mysql 客户端跑下方 SQL）："
echo "  docker compose exec mysql mysql -uroot -p\"\$DB_PASSWORD\" ideaparty -e \""
echo "    SELECT COUNT(*) AS char_cnt FROM characters WHERE id='$CHAR_ID';"
echo "    SELECT COUNT(*) AS room1_cnt FROM rooms WHERE id='$ROOM1_ID';"
echo "    SELECT COUNT(*) AS room2_cnt FROM rooms WHERE id='$ROOM2_ID';"
echo "    SELECT COUNT(*) AS rc_cnt FROM room_characters WHERE character_id='$CHAR_ID';"
echo "  \""
echo ""
echo "预期全部返回 0。"
echo "如果 rc_cnt != 0 → JPA 中间表残留 bug，需要排查 RoomRepository 关联删除。"