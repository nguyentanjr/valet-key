#!/bin/bash

echo "========================================="
echo "TEST: Nginx có forward cookie không?"
echo "========================================="
echo ""

# Test 1: Gửi cookie giả lập
echo "1️⃣ Gửi cookie giả: SESSION=test-fake-session-id"
echo "Request:"
echo "  curl -H 'Cookie: SESSION=test-fake-session-id' http://localhost/whoami"
echo ""
echo "Response:"
curl -s -H 'Cookie: SESSION=test-fake-session-id' http://localhost/whoami
echo ""
echo ""

# Test 2: Gọi /whoami lần 1 để tạo session
echo "========================================="
echo "2️⃣ Tạo session mới (lần 1)"
echo "========================================="
RESPONSE1=$(curl -c /tmp/cookies.txt -b /tmp/cookies.txt -s http://localhost/whoami)
echo "$RESPONSE1"
SESSION_ID1=$(echo "$RESPONSE1" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
echo ""
echo "Cookie được set:"
cat /tmp/cookies.txt | grep SESSION || echo "❌ Không có cookie SESSION!"
echo ""
echo "Session ID: $SESSION_ID1"
echo ""

# Test 3: Gọi /whoami lần 2 với cookie từ lần 1
echo "========================================="
echo "3️⃣ Reuse session (lần 2)"
echo "========================================="
RESPONSE2=$(curl -c /tmp/cookies.txt -b /tmp/cookies.txt -s http://localhost/whoami)
echo "$RESPONSE2"
SESSION_ID2=$(echo "$RESPONSE2" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
echo ""
echo "Session ID: $SESSION_ID2"
echo ""

# Check kết quả
echo "========================================="
echo "KẾT QUẢ:"
echo "========================================="
if [ "$SESSION_ID1" = "$SESSION_ID2" ]; then
    echo "✅ PASS: Session ID giống nhau!"
    echo "   Nginx ĐÃ forward cookie đúng!"
else
    echo "❌ FAIL: Session ID khác nhau!"
    echo "   Session 1: $SESSION_ID1"
    echo "   Session 2: $SESSION_ID2"
    echo ""
    echo "🔍 NGUYÊN NHÂN: Nginx KHÔNG forward cookie từ client lên backend!"
fi

# Cleanup
rm -f /tmp/cookies.txt

echo ""
echo "========================================="
echo "DEBUG: Check Redis"
echo "========================================="
echo "Tất cả session keys trong Redis:"
docker exec valet_key_redis redis-cli KEYS "spring:session:*" 2>/dev/null | head -10
echo ""
echo "Số lượng session trong Redis:"
docker exec valet_key_redis redis-cli KEYS "spring:session:sessions:*" 2>/dev/null | wc -l

