#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

echo -e "${BOLD}${CYAN}"
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║        🚀 CONCURRENT REQUESTS TEST (10 parallel)              ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Create temp dir for parallel results
TEMP_DIR=$(mktemp -d)

# Function to send request
send_request() {
    local req_num=$1
    local cookie_file=$2
    local output_file="${TEMP_DIR}/result_${req_num}.txt"
    
    RESPONSE=$(curl -b "$cookie_file" -s http://localhost/whoami)
    SESSION_ID=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
    BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
    
    echo "${req_num}|${SESSION_ID}|${BACKEND}" > "$output_file"
}

# Step 1: Create session
echo -e "${YELLOW}📝 Step 1: Tạo session...${NC}"
RESPONSE=$(curl -c /tmp/concurrent-test.txt -b /tmp/concurrent-test.txt -s http://localhost/whoami)
ORIGINAL_SESSION=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
FIRST_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)

echo -e "${GREEN}✅ Session: ${ORIGINAL_SESSION}${NC}"
echo -e "${MAGENTA}🖥️  Backend: ${FIRST_BACKEND}${NC}"
echo ""

# Step 2: Send concurrent requests
echo -e "${YELLOW}🚀 Step 2: Gửi 10 requests ĐỒNG THỜI...${NC}"
echo ""

echo -e "${BOLD}Sending requests in parallel...${NC}"
for i in {1..10}; do
    send_request $i /tmp/concurrent-test.txt &
done

# Wait for all background jobs
wait

echo -e "${GREEN}✅ All requests completed!${NC}"
echo ""

# Step 3: Display results
echo -e "${BOLD}${CYAN}📊 RESULTS:${NC}"
echo ""
echo -e "${BOLD}REQ# | BACKEND               | SESSION ID                           | STATUS${NC}"
echo "─────┼───────────────────────┼──────────────────────────────────────┼────────"

declare -A backend_count
SUCCESS=0
FAIL=0

for i in {1..10}; do
    if [ -f "${TEMP_DIR}/result_${i}.txt" ]; then
        IFS='|' read -r req_num session_id backend < "${TEMP_DIR}/result_${i}.txt"
        
        # Count backends
        ((backend_count[$backend]++))
        
        # Check session match
        if [ "$session_id" = "$ORIGINAL_SESSION" ]; then
            STATUS="${GREEN}✅ MATCH${NC}"
            ((SUCCESS++))
        else
            STATUS="${RED}❌ MISMATCH${NC}"
            ((FAIL++))
        fi
        
        printf " %-3s │ ${MAGENTA}%-21s${NC} │ %-36s │ %s\n" \
            "$req_num" \
            "${backend:0:21}" \
            "$session_id" \
            "$STATUS"
    fi
done

echo "─────┴───────────────────────┴──────────────────────────────────────┴────────"
echo ""

# Statistics
echo -e "${BOLD}${YELLOW}📈 STATISTICS:${NC}"
echo ""

echo -e "${BOLD}Session Persistence:${NC}"
echo -e "  ✅ Success: ${GREEN}${SUCCESS}/10${NC}"
echo -e "  ❌ Failed:  ${RED}${FAIL}/10${NC}"
echo ""

echo -e "${BOLD}Backend Distribution (Concurrent):${NC}"
for backend in "${!backend_count[@]}"; do
    count=${backend_count[$backend]}
    percentage=$(awk "BEGIN {printf \"%.1f\", ($count/10)*100}")
    echo -e "  ${MAGENTA}${backend:0:21}${NC}: $count requests ($percentage%)"
done
echo ""

# Verdict
if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}${BOLD}🎉 SUCCESS! Redis Session hoạt động với concurrent requests!${NC}"
else
    echo -e "${RED}${BOLD}❌ FAILED! Có vấn đề với session persistence!${NC}"
fi

# Cleanup
rm -rf "$TEMP_DIR"
rm -f /tmp/concurrent-test.txt

echo ""
echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"

