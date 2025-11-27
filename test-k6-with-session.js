import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,           // 10 concurrent users
  duration: '10s',   // Run for 10 seconds
};

// Setup: Tạo session TRƯỚC KHI test bắt đầu
export function setup() {
  console.log('📝 Setup: Tạo session mới cho tất cả VUs...');
  
  // Tạo session mới
  const res = http.get('http://localhost/whoami');
  
  // Extract cookie SESSION từ response
  const sessionCookie = res.cookies['SESSION'];
  if (!sessionCookie || !sessionCookie[0]) {
    throw new Error('❌ Không lấy được SESSION cookie từ backend!');
  }
  
  const sessionValue = sessionCookie[0].value;
  
  // Extract session ID từ response body
  const match = res.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const sessionId = match ? match[1] : 'UNKNOWN';
  
  console.log(`✅ Session created: ${sessionId}`);
  console.log(`   Cookie value: ${sessionValue.substring(0, 30)}...`);
  
  // Return session data cho tất cả VUs
  return {
    sessionCookie: sessionValue,
    sessionId: sessionId,
  };
}

// Main test function - chạy cho MỖI VU
export default function (data) {
  // Sử dụng CÙNG session cookie cho tất cả requests
  const res = http.get('http://localhost/whoami', {
    headers: {
      'Cookie': `SESSION=${data.sessionCookie}`,
    },
  });
  
  // Extract session ID từ response
  const match = res.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const currentSessionId = match ? match[1] : 'NONE';
  
  // Verify session ID khớp
  const sessionMatches = currentSessionId === data.sessionId;
  
  check(res, {
    'status is 200': (r) => r.status === 200,
    'session ID matches': () => sessionMatches,
  });
  
  // Log nếu session không khớp
  if (!sessionMatches) {
    console.log(`❌ VU ${__VU}: Session mismatch! Expected: ${data.sessionId}, Got: ${currentSessionId}`);
  }
  
  sleep(1);
}

// Teardown: In summary sau khi test xong
export function teardown(data) {
  console.log('\n========================================');
  console.log('📊 TEST SUMMARY:');
  console.log('========================================');
  console.log(`Session ID: ${data.sessionId}`);
  console.log(`Total VUs: ${options.vus}`);
  console.log(`Duration: ${options.duration}`);
  console.log('========================================\n');
}

