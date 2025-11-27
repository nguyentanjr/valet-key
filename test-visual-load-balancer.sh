#!/bin/bash

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color
BOLD='\033[1m'

echo -e "${BOLD}========================================="
echo -e "🎯 VISUAL LOAD BALANCER + REDIS SESSION TEST"
echo -e "=========================================${NC}"
echo ""
echo -e "${CYAN}Architecture:${NC}"
echo -e "  🌐 Client → Nginx (least_conn) → 3 Backends → Redis Session"
echo ""

# Step 1: Tạo session mới
echo -e "${BOLD}${YELLOW}STEP 1: Tạo session mới${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

RESPONSE=$(curl -c /tmp/visual-test.txt -b /tmp/visual-test.txt -s http://localhost/whoami)
SESSION_ID=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
COOKIE=$(cat /tmp/visual-test.txt 2>/dev/null | grep SESSION | awk '{print $7}')

echo -e "  📋 Session ID: ${GREEN}${SESSION_ID}${NC}"
echo -e "  🍪 Cookie: ${COOKIE:0:30}..."
echo -e "  🖥️  Backend: ${MAGENTA}${BACKEND}${NC}"
echo ""

# Step 2: Gửi nhiều requests và log chi tiết
echo -e "${BOLD}${YELLOW}STEP 2: Gửi 20 requests với session cookie${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "${BOLD}REQ# | BACKEND (Container ID)     | SESSION ID                           | STATUS${NC}"
echo -e "─────┼─────────────────────────────┼──────────────────────────────────────┼────────"

# Track statistics
declare -A backend_count
SUCCESS=0
FAIL=0

for i in {1..20}; do
    RESPONSE=$(curl -b /tmp/visual-test.txt -s http://localhost/whoami)
    CURRENT_SESSION=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
    CURRENT_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
    
    # Count requests per backend
    ((backend_count[$CURRENT_BACKEND]++))
    
    # Status check
    if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
        STATUS="${GREEN}✅ MATCH${NC}"
        ((SUCCESS++))
    else
        STATUS="${RED}❌ MISMATCH${NC}"
        ((FAIL++))
    fi
    
    # Format output
    printf " %-3s │ ${MAGENTA}%-27s${NC} │ %-36s │ %s\n" "$i" "$CURRENT_BACKEND" "${CURRENT_SESSION:0:36}" "$STATUS"
    
    # Small delay
    sleep 0.1
done

echo -e "─────┴─────────────────────────────┴──────────────────────────────────────┴────────"
echo ""

# Step 3: Statistics
echo -e "${BOLD}${YELLOW}STEP 3: Thống kê & Phân tích${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${BOLD}📊 SESSION PERSISTENCE:${NC}"
echo -e "  ✅ Success: ${GREEN}${SUCCESS}/20${NC} ($(awk "BEGIN {printf \"%.1f\", ($SUCCESS/20)*100}")%)"
echo -e "  ❌ Failed:  ${RED}${FAIL}/20${NC} ($(awk "BEGIN {printf \"%.1f\", ($FAIL/20)*100}")%)"
echo ""

echo -e "${BOLD}🖥️  LOAD BALANCER DISTRIBUTION:${NC}"
total_backends=${#backend_count[@]}
for backend in "${!backend_count[@]}"; do
    count=${backend_count[$backend]}
    percentage=$(awk "BEGIN {printf \"%.1f\", ($count/20)*100}")
    
    # Visual bar
    bar_length=$(awk "BEGIN {printf \"%d\", ($count/20)*30}")
    bar=$(printf '█%.0s' $(seq 1 $bar_length))
    
    echo -e "  ${MAGENTA}${backend}${NC}"
    echo -e "    └─ ${count} requests (${percentage}%) ${CYAN}${bar}${NC}"
done
echo ""

# Step 4: Redis Check
echo -e "${BOLD}${YELLOW}STEP 4: Kiểm tra Redis${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "  🔍 Session trong Redis:"
EXISTS=$(docker exec valet_key_redis redis-cli EXISTS "spring:session:sessions:$SESSION_ID" 2>/dev/null)
if [ "$EXISTS" = "1" ]; then
    echo -e "    ✅ ${GREEN}Session CÓ trong Redis${NC}"
    echo -e "    📋 Key: spring:session:sessions:${SESSION_ID}"
else
    echo -e "    ❌ ${RED}Session KHÔNG có trong Redis${NC}"
fi
echo ""

echo -e "  📦 Tổng số sessions trong Redis:"
TOTAL_SESSIONS=$(docker exec valet_key_redis redis-cli KEYS "spring:session:sessions:*" 2>/dev/null | wc -l)
echo -e "    ${TOTAL_SESSIONS} sessions"
echo ""

# Step 5: Final verdict
echo -e "${BOLD}${YELLOW}STEP 5: Kết luận${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "  ${GREEN}${BOLD}🎉 PERFECT! Session Persistence hoạt động 100%!${NC}"
    echo ""
    
    if [ $total_backends -gt 1 ]; then
        echo -e "  ${GREEN}✅ Load Balancing: HOẠT ĐỘNG${NC}"
        echo -e "     → Requests được phân phối đến ${total_backends} backends"
        echo ""
        echo -e "  ${GREEN}✅ Redis Session: HOẠT ĐỘNG${NC}"
        echo -e "     → Session được share giữa các backends"
        echo ""
        echo -e "  ${CYAN}${BOLD}🏆 KIẾN TRÚC: LEAST_CONN + REDIS SESSION${NC}"
        echo -e "     ${CYAN}→ Production-ready distributed system!${NC}"
    else
        echo -e "  ${YELLOW}⚠️  Chỉ 1 backend đang nhận requests${NC}"
        echo -e "     → Check xem các backend khác có đang chạy không"
    fi
else
    echo -e "  ${RED}${BOLD}❌ FAILED: Có ${FAIL}/${SUCCESS+FAIL} requests mất session!${NC}"
    echo ""
    echo -e "  ${RED}🔍 NGUYÊN NHÂN CÓ THỂ:${NC}"
    echo -e "     1. Redis Session không hoạt động đúng"
    echo -e "     2. Cookie không được forward qua Nginx"
    echo -e "     3. Backend không load session từ Redis"
fi

echo ""
echo -e "${BOLD}=========================================${NC}"

# Cleanup
rm -f /tmp/visual-test.txt

