import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '10s', target: 10 },  // Ramp up to 10 users
    { duration: '20s', target: 10 },  // Stay at 10 users
    { duration: '10s', target: 0 },   // Ramp down
  ],
};

export default function () {
  // Request 1: Tạo session
  let res1 = http.get('http://localhost/whoami');
  
  // Lấy cookie từ response
  const cookies = res1.cookies;
  let sessionCookie = '';
  for (const [key, value] of Object.entries(cookies)) {
    if (key === 'SESSION') {
      sessionCookie = value[0].value;
      break;
    }
  }
  
  // Extract session ID từ response body
  const sessionId1 = res1.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const sid1 = sessionId1 ? sessionId1[1] : 'NONE';
  
  check(res1, {
    'status is 200': (r) => r.status === 200,
    'has session cookie': () => sessionCookie !== '',
    'has session ID': () => sid1 !== 'NONE',
  });
  
  sleep(1);
  
  // Request 2: Reuse session với cookie
  let res2 = http.get('http://localhost/whoami', {
    headers: {
      'Cookie': `SESSION=${sessionCookie}`,
    },
  });
  
  const sessionId2 = res2.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const sid2 = sessionId2 ? sessionId2[1] : 'NONE';
  
  check(res2, {
    'status is 200 (2nd request)': (r) => r.status === 200,
    'session ID matches (2nd request)': () => sid1 === sid2,
  });
  
  sleep(1);
  
  // Request 3: Reuse session lần nữa
  let res3 = http.get('http://localhost/whoami', {
    headers: {
      'Cookie': `SESSION=${sessionCookie}`,
    },
  });
  
  const sessionId3 = res3.body.match(/SESSION_ID = ([a-f0-9\-]+)/);
  const sid3 = sessionId3 ? sessionId3[1] : 'NONE';
  
  check(res3, {
    'status is 200 (3rd request)': (r) => r.status === 200,
    'session ID matches (3rd request)': () => sid1 === sid3,
  });
  
  // Log nếu session ID không khớp (debug)
  if (sid1 !== sid2 || sid1 !== sid3) {
    console.log(`❌ Session mismatch! 1st: ${sid1}, 2nd: ${sid2}, 3rd: ${sid3}`);
  }
}

