#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

echo -e "${BOLD}${CYAN}"
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║   🔥 REAL-TIME LOAD BALANCER + REDIS SESSION MONITOR 🔥      ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

echo -e "${YELLOW}📝 Tạo session mới...${NC}"
RESPONSE=$(curl -c /tmp/realtime-test.txt -b /tmp/realtime-test.txt -s http://localhost/whoami)
SESSION_ID=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
FIRST_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)

echo -e "${GREEN}✅ Session created: ${SESSION_ID}${NC}"
echo -e "${MAGENTA}🖥️  First backend: ${FIRST_BACKEND}${NC}"
echo ""

echo -e "${BOLD}${CYAN}Gửi requests liên tục (Ctrl+C để dừng)...${NC}"
echo ""
echo -e "${BOLD}TIME     | BACKEND          | SESSION (first 8 chars) | STATUS${NC}"
echo "─────────┼──────────────────┼─────────────────────────┼────────"

declare -A backend_count
COUNT=0

while true; do
    RESPONSE=$(curl -b /tmp/realtime-test.txt -s http://localhost/whoami)
    CURRENT_SESSION=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
    CURRENT_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
    
    # Get current time
    TIMESTAMP=$(date +"%H:%M:%S")
    
    # Count
    ((COUNT++))
    ((backend_count[$CURRENT_BACKEND]++))
    
    # Session short
    SESSION_SHORT="${CURRENT_SESSION:0:8}"
    
    # Status
    if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
        STATUS="${GREEN}✅ MATCH   ${NC}"
    else
        STATUS="${RED}❌ MISMATCH${NC}"
    fi
    
    # Backend color
    case "$CURRENT_BACKEND" in
        *"backend-1"*|*"b65b"*)
            BACKEND_COLOR="${MAGENTA}"
            ;;
        *"backend-2"*|*"489761"*)
            BACKEND_COLOR="${CYAN}"
            ;;
        *"backend-3"*)
            BACKEND_COLOR="${BLUE}"
            ;;
        *)
            BACKEND_COLOR="${YELLOW}"
            ;;
    esac
    
    printf " %-8s │ ${BACKEND_COLOR}%-16s${NC} │ %-23s │ %s\n" \
        "$TIMESTAMP" \
        "${CURRENT_BACKEND:0:16}" \
        "$SESSION_SHORT..." \
        "$STATUS"
    
    # Print summary every 10 requests
    if [ $((COUNT % 10)) -eq 0 ]; then
        echo "─────────┴──────────────────┴─────────────────────────┴────────"
        echo -e "${BOLD}📊 Summary after $COUNT requests:${NC}"
        for backend in "${!backend_count[@]}"; do
            count=${backend_count[$backend]}
            percentage=$(awk "BEGIN {printf \"%.1f\", ($count/$COUNT)*100}")
            echo -e "   ${MAGENTA}${backend:0:16}${NC}: $count requests ($percentage%)"
        done
        echo "─────────┬──────────────────┬─────────────────────────┬────────"
    fi
    
    sleep 1
done

# Cleanup (khi Ctrl+C)
trap "rm -f /tmp/realtime-test.txt; echo ''; echo 'Test stopped.'; exit" INT

