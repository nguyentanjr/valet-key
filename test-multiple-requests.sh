#!/bin/bash

echo "========================================="
echo "TEST: Session Persistence với nhiều requests"
echo "========================================="
echo ""

# Tạo session mới
echo "1️⃣ Tạo session mới..."
RESPONSE1=$(curl -c /tmp/session-test.txt -b /tmp/session-test.txt -s http://localhost/whoami)
SESSION_ID=$(echo "$RESPONSE1" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
BACKEND1=$(echo "$RESPONSE1" | grep -oP 'BACKEND → \K[^ ]+' | head -1)

echo "   Session ID: $SESSION_ID"
echo "   Backend: $BACKEND1"
echo ""

# Gọi 20 requests liên tiếp
echo "2️⃣ Gọi 20 requests với cùng session cookie..."
echo ""

SUCCESS=0
FAIL=0
BACKENDS=()

for i in {1..20}; do
    RESPONSE=$(curl -b /tmp/session-test.txt -s http://localhost/whoami)
    CURRENT_SESSION=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
    CURRENT_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
    
    if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
        echo "   Request $i: ✅ Session match (Backend: $CURRENT_BACKEND)"
        ((SUCCESS++))
        
        # Track backend instances
        if [[ ! " ${BACKENDS[@]} " =~ " ${CURRENT_BACKEND} " ]]; then
            BACKENDS+=("$CURRENT_BACKEND")
        fi
    else
        echo "   Request $i: ❌ Session mismatch! Got: $CURRENT_SESSION (Backend: $CURRENT_BACKEND)"
        ((FAIL++))
    fi
    
    sleep 0.1
done

echo ""
echo "========================================="
echo "KẾT QUẢ:"
echo "========================================="
echo "✅ Success: $SUCCESS/20"
echo "❌ Fail: $FAIL/20"
echo ""
echo "Backend instances được route đến:"
for backend in "${BACKENDS[@]}"; do
    echo "   - $backend"
done
echo ""

if [ $FAIL -eq 0 ]; then
    echo "🎉 PERFECT! Session persistence hoạt động 100%!"
    echo ""
    echo "📊 STICKY SESSION STATUS:"
    if [ ${#BACKENDS[@]} -eq 1 ]; then
        echo "   ✅ STICKY: Tất cả requests đều đến cùng 1 backend"
    else
        echo "   ⚠️  NON-STICKY: Requests được route đến ${#BACKENDS[@]} backends khác nhau"
        echo "   💡 Điều này OK nếu Redis Session hoạt động (session được share giữa backends)"
    fi
else
    echo "❌ FAIL: Có $FAIL/$((SUCCESS+FAIL)) requests bị mất session!"
    echo ""
    echo "🔍 NGUYÊN NHÂN CÓ THỂ:"
    echo "   1. Cookie không được gửi kèm trong request"
    echo "   2. Backend không load session từ Redis"
    echo "   3. Session bị expire giữa chừng"
fi

echo ""
echo "========================================="
echo "CHECK REDIS:"
echo "========================================="
echo "Session trong Redis:"
docker exec valet_key_redis redis-cli EXISTS "spring:session:sessions:$SESSION_ID" 2>/dev/null
echo ""
echo "Tổng số sessions trong Redis:"
docker exec valet_key_redis redis-cli KEYS "spring:session:sessions:*" 2>/dev/null | wc -l

# Cleanup
rm -f /tmp/session-test.txt

