#!/bin/bash

# Script để test xem frontend có gửi bao nhiêu requests khi bấm 1 nút

echo "🔍 TEST: Kiểm tra số lượng requests từ frontend"
echo "================================================"
echo ""
echo "📝 Hướng dẫn:"
echo "1. Mở 3 terminal và chạy:"
echo "   Terminal 1: docker logs -f valet-key-backend-1 | grep -E '(GET|POST|PUT|DELETE)'"
echo "   Terminal 2: docker logs -f valet-key-backend-2 | grep -E '(GET|POST|PUT|DELETE)'"
echo "   Terminal 3: docker logs -f valet-key-backend-3 | grep -E '(GET|POST|PUT|DELETE)'"
echo ""
echo "2. Hoặc chạy script này để xem tất cả logs:"
echo ""

# Lấy container names
BACKEND1=$(docker ps --filter "name=backend-1" --format "{{.Names}}" | head -1)
BACKEND2=$(docker ps --filter "name=backend-2" --format "{{.Names}}" | head -1)
BACKEND3=$(docker ps --filter "name=backend-3" --format "{{.Names}}" | head -1)

if [ -z "$BACKEND1" ] || [ -z "$BACKEND2" ]; then
    echo "❌ Không tìm thấy backend containers!"
    echo "Đang chạy containers:"
    docker ps --filter "name=backend" --format "table {{.Names}}\t{{.Status}}"
    exit 1
fi

echo "✅ Tìm thấy backends:"
echo "   - $BACKEND1"
echo "   - $BACKEND2"
[ -n "$BACKEND3" ] && echo "   - $BACKEND3"
echo ""
echo "📊 Đang monitor logs (nhấn Ctrl+C để dừng)..."
echo ""

# Monitor logs với timestamp
docker logs -f --tail=0 $BACKEND1 2>&1 | while read line; do
    echo "[BACKEND-1] $line"
done &
PID1=$!

docker logs -f --tail=0 $BACKEND2 2>&1 | while read line; do
    echo "[BACKEND-2] $line"
done &
PID2=$!

if [ -n "$BACKEND3" ]; then
    docker logs -f --tail=0 $BACKEND3 2>&1 | while read line; do
        echo "[BACKEND-3] $line"
    done &
    PID3=$!
fi

# Cleanup on exit
trap "kill $PID1 $PID2 ${PID3:-} 2>/dev/null; exit" INT TERM

wait

