#!/bin/bash

# Test Spring Session với Cookie persistence

echo "========================================="
echo "TEST 1: Gọi /whoami lần 1 - Tạo session mới"
echo "========================================="
RESPONSE1=$(curl -c cookies.txt -b cookies.txt http://localhost/whoami 2>/dev/null)
echo "$RESPONSE1"
echo ""

echo "Cookie được set:"
cat cookies.txt | grep SESSION
echo ""

echo "========================================="
echo "TEST 2: Gọi /whoami lần 2 - Reuse session"
echo "========================================="
RESPONSE2=$(curl -c cookies.txt -b cookies.txt http://localhost/whoami 2>/dev/null)
echo "$RESPONSE2"
echo ""

echo "========================================="
echo "TEST 3: Gọi /whoami lần 3 - Reuse session"
echo "========================================="
RESPONSE3=$(curl -c cookies.txt -b cookies.txt http://localhost/whoami 2>/dev/null)
echo "$RESPONSE3"
echo ""

echo "========================================="
echo "KẾT QUẢ:"
echo "========================================="

# Extract session IDs
SESSION1=$(echo "$RESPONSE1" | grep -oP 'SESSION_ID = \K[^"]+' || echo "NONE")
SESSION2=$(echo "$RESPONSE2" | grep -oP 'SESSION_ID = \K[^"]+' || echo "NONE")
SESSION3=$(echo "$RESPONSE3" | grep -oP 'SESSION_ID = \K[^"]+' || echo "NONE")

echo "Session ID lần 1: $SESSION1"
echo "Session ID lần 2: $SESSION2"
echo "Session ID lần 3: $SESSION3"
echo ""

if [ "$SESSION1" = "$SESSION2" ] && [ "$SESSION2" = "$SESSION3" ] && [ "$SESSION1" != "NONE" ]; then
    echo "✅ PASS: Session ID giống nhau! Session persistence hoạt động!"
else
    echo "❌ FAIL: Session ID khác nhau! Session KHÔNG được persist!"
fi

echo ""
echo "========================================="
echo "CHECK REDIS:"
echo "========================================="
echo "Kiểm tra key trong Redis:"
docker exec valet_key_redis redis-cli KEYS "spring:session:sessions:$SESSION1"
echo ""
echo "Chi tiết session trong Redis:"
docker exec valet_key_redis redis-cli HGETALL "spring:session:sessions:$SESSION1" | head -20

# Cleanup
rm -f cookies.txt

