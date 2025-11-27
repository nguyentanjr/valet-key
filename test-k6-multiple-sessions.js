import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,           // 10 concurrent users
  duration: '10s',   // Run for 10 seconds
};

// Setup: Tạo 1 session riêng cho MỖI VU
export function setup() {
  console.log('📝 Setup: Chuẩn bị tạo sessions cho các VUs...');
  return { message: 'Each VU will create its own session' };
}

// Main test function
export default function () {
  // MỖI VU tự tạo session của riêng mình (lần đầu)
  if (__ITER === 0) {
    // Iteration đầu tiên: Tạo session mới
    const createRes = http.get('http://localhost/whoami');
    
    const sessionCookie = createRes.cookies['SESSION'];
    if (!sessionCookie || !sessionCookie[0]) {
      console.log(`❌ VU ${__VU}: Không lấy được SESSION cookie!`);
      return;
    }
    
    // Lưu session cookie vào VU context (global cho VU này)
    if (typeof globalThis.mySession === 'undefined') {
      const sessionValue = sessionCookie[0].value;
      const match = createRes.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
      const sessionId = match ? match[1] : 'UNKNOWN';
      
      globalThis.mySession = {
        cookie: sessionValue,
        id: sessionId,
      };
      
      console.log(`✅ VU ${__VU}: Created session ${sessionId.substring(0, 8)}...`);
    }
  }
  
  // Sử dụng session đã tạo
  if (typeof globalThis.mySession === 'undefined') {
    console.log(`❌ VU ${__VU}: Không có session!`);
    return;
  }
  
  const res = http.get('http://localhost/whoami', {
    headers: {
      'Cookie': `SESSION=${globalThis.mySession.cookie}`,
    },
  });
  
  // Extract session ID từ response
  const match = res.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const currentSessionId = match ? match[1] : 'NONE';
  
  // Verify session ID khớp
  const sessionMatches = currentSessionId === globalThis.mySession.id;
  
  check(res, {
    'status is 200': (r) => r.status === 200,
    'session ID matches': () => sessionMatches,
  });
  
  // Log nếu session không khớp
  if (!sessionMatches && __ITER > 0) {
    console.log(`❌ VU ${__VU} (iter ${__ITER}): Session mismatch! Expected: ${globalThis.mySession.id}, Got: ${currentSessionId}`);
  }
  
  sleep(1);
}

export function teardown(data) {
  console.log('\n========================================');
  console.log('📊 TEST COMPLETE');
  console.log('========================================');
  console.log(`Total VUs: ${options.vus} (mỗi VU có session riêng)`);
  console.log(`Duration: ${options.duration}`);
  console.log('========================================\n');
}

