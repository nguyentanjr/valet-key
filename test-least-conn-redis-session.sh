#!/bin/bash

echo "========================================="
echo "TEST: LEAST_CONN + REDIS SESSION"
echo "========================================="
echo "Load balancing: least_conn (không sticky)"
echo "Session sharing: Redis"
echo ""

# Tạo session mới
echo "1️⃣ Tạo session mới..."
RESPONSE1=$(curl -c /tmp/test-lc.txt -b /tmp/test-lc.txt -s http://localhost/whoami)
SESSION_ID=$(echo "$RESPONSE1" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
BACKEND1=$(echo "$RESPONSE1" | grep -oP 'BACKEND → \K[^ ]+' | head -1)

echo "   Session ID: $SESSION_ID"
echo "   Backend: $BACKEND1"
echo ""

# Đợi 1s để backend có thể thay đổi
sleep 1

# Gọi 30 requests liên tiếp với CÙNG SESSION COOKIE
echo "2️⃣ Gọi 30 requests với cùng session cookie..."
echo "   (Kiểm tra xem có được route đến nhiều backends khác nhau không)"
echo ""

SUCCESS=0
FAIL=0
declare -A BACKEND_COUNT

for i in {1..30}; do
    RESPONSE=$(curl -b /tmp/test-lc.txt -s http://localhost/whoami)
    CURRENT_SESSION=$(echo "$RESPONSE" | grep -oP 'SESSION_ID = \K[^"]+' | head -1)
    CURRENT_BACKEND=$(echo "$RESPONSE" | grep -oP 'BACKEND → \K[^ ]+' | head -1)
    
    # Count requests per backend
    ((BACKEND_COUNT[$CURRENT_BACKEND]++))
    
    if [ "$CURRENT_SESSION" = "$SESSION_ID" ]; then
        if [ $i -le 5 ] || [ $i -ge 26 ]; then
            echo "   Request $i: ✅ Session match (Backend: $CURRENT_BACKEND)"
        elif [ $i -eq 6 ]; then
            echo "   ..."
        fi
        ((SUCCESS++))
    else
        echo "   Request $i: ❌ Session MISMATCH! Got: $CURRENT_SESSION (Backend: $CURRENT_BACKEND)"
        ((FAIL++))
    fi
    
    # Thêm delay nhỏ để connections có thể close
    sleep 0.05
done

echo ""
echo "========================================="
echo "KẾT QUẢ:"
echo "========================================="
echo "✅ Session Match: $SUCCESS/30"
echo "❌ Session Mismatch: $FAIL/30"
echo ""

echo "📊 DISTRIBUTION - Requests per backend:"
for backend in "${!BACKEND_COUNT[@]}"; do
    count=${BACKEND_COUNT[$backend]}
    percentage=$(awk "BEGIN {printf \"%.1f\", ($count/30)*100}")
    echo "   Backend $backend: $count requests ($percentage%)"
done
echo ""

echo "🔍 PHÂN TÍCH:"
if [ $FAIL -eq 0 ]; then
    echo "   ✅ Session Persistence: HOÀN HẢO!"
    echo "      → Redis Session đang hoạt động đúng"
    echo "      → Tất cả backends đều load được session từ Redis"
    echo ""
    
    num_backends=${#BACKEND_COUNT[@]}
    if [ $num_backends -gt 1 ]; then
        echo "   ✅ Load Balancing: ĐANG HOẠT ĐỘNG!"
        echo "      → Requests được phân phối đến $num_backends backends"
        echo "      → least_conn đang route requests đúng cách"
        echo ""
        echo "   🎯 KIẾN TRÚC: LEAST_CONN + REDIS SESSION"
        echo "      → Best practice cho distributed system!"
    else
        echo "   ⚠️  Chỉ có 1 backend nhận requests"
        echo "      → Có thể các backends khác đang offline"
        echo "      → Hoặc load quá thấp nên không cần distribute"
    fi
else
    echo "   ❌ FAIL: Có $FAIL requests mất session!"
    echo "      → Redis Session có vấn đề"
    echo "      → Cần check log backend"
fi

echo ""
echo "========================================="
echo "REDIS STATUS:"
echo "========================================="
echo "Session ID: $SESSION_ID"
echo ""
echo "Session có trong Redis không?"
EXISTS=$(docker exec valet_key_redis redis-cli EXISTS "spring:session:sessions:$SESSION_ID" 2>/dev/null)
if [ "$EXISTS" = "1" ]; then
    echo "   ✅ CÓ - Session được lưu trong Redis"
else
    echo "   ❌ KHÔNG - Session KHÔNG có trong Redis!"
fi
echo ""

echo "Tổng số sessions trong Redis:"
TOTAL=$(docker exec valet_key_redis redis-cli KEYS "spring:session:sessions:*" 2>/dev/null | wc -l)
echo "   $TOTAL sessions"
echo ""

echo "Chi tiết session attributes:"
docker exec valet_key_redis redis-cli HKEYS "spring:session:sessions:$SESSION_ID" 2>/dev/null | head -10

# Cleanup
rm -f /tmp/test-lc.txt

echo ""
echo "========================================="
echo "KẾT LUẬN:"
echo "========================================="
if [ $FAIL -eq 0 ] && [ $num_backends -gt 1 ]; then
    echo "🎉 PERFECT! LEAST_CONN + REDIS SESSION đang hoạt động!"
    echo ""
    echo "✅ Load balancing: Requests được phân phối đều"
    echo "✅ Session sharing: Session được load từ Redis"
    echo "✅ High availability: Sẵn sàng cho production!"
elif [ $FAIL -eq 0 ] && [ $num_backends -eq 1 ]; then
    echo "✅ Redis Session hoạt động, nhưng chỉ 1 backend active"
    echo "💡 Start thêm backend instances để test load balancing"
elif [ $FAIL -gt 0 ]; then
    echo "❌ Redis Session có vấn đề, cần debug!"
fi

