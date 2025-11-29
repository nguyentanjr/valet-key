# Kiến Trúc Hệ Thống Phân Tán (Distributed Systems Architecture)

Tài liệu này mô tả chi tiết **TẤT CẢ** các distributed systems và components trong project, bao gồm kiến trúc, luồng hoạt động, và cách chúng tương tác với nhau.

---

## 📋 Mục Lục

1. [Tổng Quan Kiến Trúc](#1-tổng-quan-kiến-trúc)
2. [Nginx Load Balancer](#2-nginx-load-balancer)
3. [Spring Session với Redis](#3-spring-session-với-redis)
4. [Bucket4j Rate Limiting với Redis](#4-bucket4j-rate-limiting-với-redis)
5. [Redis Cache](#5-redis-cache)
6. [Circuit Breaker (Resilience4j)](#6-circuit-breaker-resilience4j)
7. [WebSocket Real-time Communication](#7-websocket-real-time-communication)
8. [MySQL Database](#8-mysql-database)
9. [Luồng Hoạt Động Tổng Thể](#9-luồng-hoạt-động-tổng-thể)
10. [Tương Tác Giữa Các Components](#10-tương-tác-giữa-các-components)

---

## 1. Tổng Quan Kiến Trúc

### 1.1. Kiến Trúc Tổng Thể

```
┌─────────────────────────────────────────────────────────────────┐
│                         CLIENT (Browser)                          │
│                    http://localhost:3000                          │
└────────────────────────────┬──────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    NGINX LOAD BALANCER                           │
│                    http://localhost:80                           │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Load Balancing Strategy: least_conn                      │  │
│  │  Rate Limiting: Nginx limit_req (first layer)             │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────┬──────────────┬──────────────┬───────────────────────┘
             │              │              │
      ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
      │  Backend 1  │ │  Backend 2  │ │  Backend 3  │
      │  :8080      │ │  :8080      │ │  :8080      │
      └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
             │              │              │
             └──────────────┼──────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
    ┌────▼────┐       ┌────▼────┐       ┌────▼────┐
    │  Redis  │       │  MySQL  │       │  Azure   │
    │  :6379  │       │  :3306  │       │  Blob    │
    └─────────┘       └─────────┘       └──────────┘
```

### 1.2. Các Distributed Components

| Component | Mục Đích | Công Nghệ | Distributed? |
|-----------|----------|-----------|--------------|
| **Nginx Load Balancer** | Phân phối requests đến multiple backends | Nginx `least_conn` | ✅ Yes |
| **Spring Session** | Quản lý session shared giữa các backends | Redis | ✅ Yes |
| **Bucket4j Rate Limiting** | Giới hạn số requests per user/IP | Redis + Bucket4j | ✅ Yes |
| **Redis Cache** | Cache dữ liệu shared giữa các backends | Redis | ✅ Yes |
| **Circuit Breaker** | Bảo vệ khi Azure service fail | Resilience4j (in-memory) | ❌ No (per instance) |
| **WebSocket** | Real-time upload progress | Spring WebSocket | ❌ No (per instance) |
| **MySQL Database** | Lưu trữ dữ liệu persistent | MySQL | ✅ Yes (shared) |

---

## 2. Nginx Load Balancer

### 2.1. Kiến Trúc

```
Client Request
     │
     ▼
┌─────────────────────────────────────────┐
│         NGINX (Port 80)                  │
│  ┌───────────────────────────────────┐ │
│  │  Rate Limiting (First Layer)       │ │
│  │  - login_limit: 5r/m               │ │
│  │  - upload_limit: 30r/m              │ │
│  │  - api_limit: 100r/m               │ │
│  └───────────────────────────────────┘ │
│  ┌───────────────────────────────────┐ │
│  │  Load Balancing: least_conn       │ │
│  │  - Chọn backend có ít connections │ │
│  └───────────────────────────────────┘ │
└────────────┬────────────┬───────────────┘
             │            │
      ┌──────▼──────┐ ┌───▼──────┐
      │  Backend 1  │ │ Backend 2│
      └─────────────┘ └──────────┘
```

### 2.2. Cấu Hình

**File:** `nginx.conf`

```nginx
upstream backend {
    least_conn;  # ✅ Chọn backend có ít connections nhất
    server host.docker.internal:8080;
    server backend1:8080;
    server backend2:8080;
    keepalive 32;
}
```

### 2.3. Luồng Hoạt Động

1. **Client gửi request** → `http://localhost:80/api/files/list`
2. **Nginx nhận request** → Kiểm tra rate limit (first layer)
3. **Nếu vượt rate limit** → Trả về `429 Too Many Requests`
4. **Nếu OK** → Chọn backend theo `least_conn` strategy
5. **Forward request** → Backend được chọn xử lý
6. **Backend response** → Nginx forward về client

### 2.4. Tại Sao Dùng `least_conn`?

- **Cân bằng tải thông minh:** Phân phối requests dựa trên số connections đang active, không phải round-robin
- **Tối ưu hiệu năng:** Backend ít connections hơn sẽ xử lý nhanh hơn
- **Không cần sticky session:** Vì session được share qua Redis, nên không cần route cùng user đến cùng backend

### 2.5. Rate Limiting ở Nginx (First Layer)

Nginx có **2 lớp rate limiting:**

1. **Nginx `limit_req`** (First Layer):
   - Chặn ở tầng Nginx trước khi đến backend
   - Nhanh, không tốn tài nguyên backend
   - Config: `limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/m;`

2. **Bucket4j Rate Limiting** (Second Layer):
   - Chặn ở tầng application (Spring Interceptor)
   - Linh hoạt hơn, có thể rate limit theo user ID, IP, token
   - Shared state qua Redis → Tất cả backends thấy cùng limit

---

## 3. Spring Session với Redis

### 3.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT (Browser)                          │
│  Cookie: SESSION=abc123...                                    │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              NGINX Load Balancer                            │
│  Forward Cookie: SESSION=abc123...                          │
└────────────┬──────────────┬──────────────┬──────────────────┘
             │              │              │
      ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
      │  Backend 1  │ │  Backend 2  │ │  Backend 3  │
      │             │ │             │ │             │
      │  Read Session│ │  Read Session│ │  Read Session│
      │  from Redis │ │  from Redis │ │  from Redis │
      └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
             │              │              │
             └──────────────┼──────────────┘
                            │
                    ┌───────▼────────┐
                    │     REDIS       │
                    │  Key: spring:   │
                    │  session:abc123│
                    │  Value: {       │
                    │    SecurityContext,│
                    │    user, ...    │
                    │  }              │
                    └─────────────────┘
```

### 3.2. Cấu Hình

**File:** `src/main/java/com/example/valetkey/config/RedisConfig.java`

```java
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)  // 30 phút
public class RedisConfig {
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new JdkSerializationRedisSerializer();  // ✅ Dùng JDK serialization cho SecurityContext
    }
}
```

**File:** `src/main/resources/application.properties`

```properties
spring.session.store-type=redis
spring.session.redis.namespace=spring:session
spring.session.timeout=1800  # 30 phút
```

### 3.3. Luồng Hoạt Động

#### 3.3.1. Login Flow

```
1. Client POST /login {username, password}
   │
   ▼
2. Nginx → Backend 1 (least_conn)
   │
   ▼
3. Backend 1: Authenticate user
   │
   ▼
4. Backend 1: Tạo SecurityContext
   │
   ▼
5. Backend 1: Lưu SecurityContext vào HttpSession
   │
   ▼
6. Spring Session: Serialize session → Redis
   Redis Key: spring:session:sessions:abc123
   Redis Value: {SecurityContext, user, ...}
   │
   ▼
7. Backend 1: Set-Cookie: SESSION=abc123
   │
   ▼
8. Client: Lưu cookie SESSION=abc123
```

#### 3.3.2. Subsequent Request Flow

```
1. Client GET /api/files/list
   Cookie: SESSION=abc123
   │
   ▼
2. Nginx → Backend 2 (least_conn chọn backend 2)
   │
   ▼
3. Backend 2: Đọc cookie SESSION=abc123
   │
   ▼
4. Backend 2: Query Redis
   Key: spring:session:sessions:abc123
   │
   ▼
5. Redis: Trả về session data
   │
   ▼
6. Spring Session: Deserialize → HttpSession
   │
   ▼
7. Backend 2: Lấy SecurityContext từ session
   │
   ▼
8. Backend 2: Xử lý request với SecurityContext
   │
   ▼
9. Response về client
```

### 3.4. Tại Sao Dùng Redis cho Session?

- **Stateless Backends:** Mỗi backend instance không lưu session trong memory → Có thể scale horizontal
- **Session Sharing:** User có thể được route đến bất kỳ backend nào, vẫn giữ session
- **Persistence:** Session được lưu trong Redis → Không mất khi restart backend
- **TTL:** Redis tự động xóa session sau 30 phút không hoạt động

### 3.5. Serialization

**Vấn đề:** `SecurityContext` chứa Java objects phức tạp, không thể serialize bằng JSON.

**Giải pháp:** Dùng `JdkSerializationRedisSerializer` để serialize toàn bộ object graph.

```java
// ❌ KHÔNG DÙNG: GenericJackson2JsonRedisSerializer
// → Không serialize được SecurityContext

// ✅ DÙNG: JdkSerializationRedisSerializer
// → Serialize toàn bộ object graph
```

---

## 4. Bucket4j Rate Limiting với Redis

### 4.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT Request                            │
│  GET /api/files/list                                         │
│  IP: 192.168.1.100                                           │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              NGINX Load Balancer                            │
│  (First Layer Rate Limit - Nginx limit_req)                │
└────────────┬──────────────┬──────────────┬──────────────────┘
             │              │              │
      ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
      │  Backend 1  │ │  Backend 2  │ │  Backend 3  │
      │             │ │             │ │             │
      │ RateLimit   │ │ RateLimit   │ │ RateLimit   │
      │ Interceptor │ │ Interceptor │ │ Interceptor │
      └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
             │              │              │
             └──────────────┼──────────────┘
                            │
                    ┌───────▼────────┐
                    │     REDIS       │
                    │  Key: ip:192.   │
                    │  168.1.100:    │
                    │  LIST_FILES    │
                    │  Value: {      │
                    │    tokens: 45, │
                    │    capacity: 60│
                    │  }             │
                    └─────────────────┘
```

### 4.2. Cấu Hình

**File:** `src/main/java/com/example/valetkey/config/RedisConfig.java`

```java
@Bean
public LettuceBasedProxyManager<String> bucket4jProxyManager(
        StatefulRedisConnection<String, byte[]> connection) {
    return LettuceBasedProxyManager.builderFor(connection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofHours(24)
                )
            )
            .build();
}
```

**File:** `src/main/java/com/example/valetkey/service/RateLimitService.java`

```java
public enum RateLimitType {
    LOGIN(5, Duration.ofMinutes(15)),
    UPLOAD_SMALL(20, Duration.ofMinutes(1)),
    LIST_FILES(60, Duration.ofMinutes(1)),
    DOWNLOAD(100, Duration.ofMinutes(1)),
    // ...
}
```

### 4.3. Luồng Hoạt Động

#### 4.3.1. Request Flow với Rate Limiting

```
1. Client GET /api/files/list
   IP: 192.168.1.100
   │
   ▼
2. Nginx → Backend 1 (least_conn)
   │
   ▼
3. Backend 1: RateLimitInterceptor.preHandle()
   │
   ▼
4. Interceptor: Xác định RateLimitType
   URI: /api/files/list → RateLimitType.LIST_FILES
   │
   ▼
5. Interceptor: Generate key
   Key: "ip:192.168.1.100:LIST_FILES"
   │
   ▼
6. RateLimitService.tryConsume(key, type)
   │
   ▼
7. RateLimitService: Resolve bucket từ Redis
   Key: "ip:192.168.1.100:LIST_FILES"
   │
   ▼
8. Bucket4j: Đọc bucket từ Redis
   - Nếu chưa có → Tạo mới với capacity=60, refill=60/min
   - Nếu có → Đọc tokens hiện tại
   │
   ▼
9. Bucket4j: tryConsume(1)
   - Nếu tokens > 0 → Consume 1 token, return true
   - Nếu tokens = 0 → Return false
   │
   ▼
10. Nếu allowed:
    - Add headers: X-RateLimit-Limit, X-RateLimit-Remaining
    - Continue request → Controller
    │
    ▼
11. Nếu blocked:
    - Return 429 Too Many Requests
    - Response: {"error": "Rate limit exceeded"}
```

#### 4.3.2. Token Bucket Algorithm

```
Bucket State trong Redis:
┌─────────────────────────────────────┐
│ Key: ip:192.168.1.100:LIST_FILES    │
│ Value: {                             │
│   capacity: 60,                      │
│   tokens: 45,                        │
│   refillRate: 60 tokens/minute,      │
│   lastRefill: 2025-11-27T10:00:00Z  │
│ }                                    │
└─────────────────────────────────────┘

Mỗi request:
1. Check tokens > 0?
   - Yes → Consume 1 token, tokens = 44
   - No → Block request

Mỗi phút:
1. Refill tokens
   - tokens = min(capacity, tokens + refillRate)
   - tokens = min(60, 44 + 60) = 60
```

### 4.4. Tại Sao Dùng Redis cho Rate Limiting?

- **Shared State:** Tất cả backends đọc cùng bucket → Rate limit được enforce globally
- **Atomic Operations:** Bucket4j dùng Redis CAS (Compare-And-Swap) → Đảm bảo thread-safe
- **TTL:** Redis tự động xóa bucket sau 24h không dùng → Tiết kiệm memory

### 4.5. Key Generation Strategy

```java
// Theo User ID (authenticated)
Key: "user:123:LIST_FILES"

// Theo IP (unauthenticated hoặc public access)
Key: "ip:192.168.1.100:LIST_FILES"

// Theo Token (public file access)
Key: "token:abc123:PUBLIC_ACCESS_TOKEN"
```

---

## 5. Redis Cache

### 5.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT Request                            │
│  GET /api/files/list?folderId=1                              │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              NGINX → Backend 1                               │
│                                                              │
│  FileController.listFiles(folderId=1)                       │
│    │                                                         │
│    ▼                                                         │
│  @Cacheable("fileList")                                     │
│    │                                                         │
│    ▼                                                         │
│  Check Redis Cache                                          │
│    │                                                         │
│    ├─ Cache HIT → Return cached data                        │
│    │                                                         │
│    └─ Cache MISS → Query DB → Save to cache → Return       │
└────────────────────────┬────────────────────────────────────┘
                         │
                    ┌────▼────────┐
                    │     REDIS    │
                    │  Key: cache: │
                    │  fileList::1 │
                    │  Value: {    │
                    │    files: [...],│
                    │    folders: [...]│
                    │  }           │
                    └──────────────┘
```

### 5.2. Cấu Hình

**File:** `src/main/java/com/example/valetkey/config/CacheConfig.java`

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
            .defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues();
        
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("fileList", defaultConfig.entryTtl(Duration.ofMinutes(1)));
        cacheConfigs.put("fileMetadata", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigs.put("folderTree", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
```

### 5.3. Luồng Hoạt Động

```
1. Client GET /api/files/list?folderId=1
   │
   ▼
2. Backend: FileController.listFiles(folderId=1)
   │
   ▼
3. Spring Cache: Check Redis
   Key: "cache:fileList::1"
   │
   ▼
4. Cache HIT?
   ├─ YES → Return cached data (không query DB)
   │
   └─ NO → Continue
      │
      ▼
5. Query MySQL Database
   SELECT * FROM files WHERE folder_id = 1
   │
   ▼
6. Save to Redis Cache
   Key: "cache:fileList::1"
   Value: {files: [...], folders: [...]}
   TTL: 1 minute
   │
   ▼
7. Return data to client
```

### 5.4. Cache Invalidation

```java
@CacheEvict(value = "fileList", key = "#folderId")
public void deleteFile(Long fileId, Long folderId) {
    // Delete file from DB
    // Cache sẽ tự động bị xóa
}
```

### 5.5. Tại Sao Dùng Redis cho Cache?

- **Shared Cache:** Tất cả backends đọc cùng cache → Giảm load DB
- **Fast Access:** Redis in-memory → Response time < 1ms
- **TTL:** Tự động expire → Không cần manual cleanup
- **Consistency:** Khi một backend update cache, tất cả backends thấy ngay

---

## 6. Circuit Breaker (Resilience4j)

### 6.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT Request                            │
│  POST /api/files/upload/sas-url                              │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              Backend 1                                       │
│                                                              │
│  AzureSasService.generateBlobReadSas()                      │
│    │                                                         │
│    ▼                                                         │
│  @CircuitBreaker(name = "azureService")                     │
│    │                                                         │
│    ├─ State: CLOSED → Call Azure API                        │
│    │                                                         │
│    ├─ State: OPEN → Return fallback (không call Azure)      │
│    │                                                         │
│    └─ State: HALF_OPEN → Test call Azure                    │
└────────────────────────┬────────────────────────────────────┘
                         │
                    ┌────▼────────┐
                    │   AZURE BLOB │
                    │   STORAGE    │
                    └──────────────┘
```

### 6.2. Cấu Hình

**File:** `src/main/resources/application.properties`

```properties
# Circuit Breaker Configuration
resilience4j.circuitbreaker.instances.azureService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.azureService.minimumNumberOfCalls=5
resilience4j.circuitbreaker.instances.azureService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.azureService.waitDurationInOpenState=60s
```

**File:** `src/main/java/com/example/valetkey/service/AzureSasService.java`

```java
@CircuitBreaker(name = "azureService", fallbackMethod = "generateBlobReadSasFallback")
public String generateBlobReadSas(String blobName) {
    // Call Azure API
}

public String generateBlobReadSasFallback(String blobName, Exception ex) {
    // Return fallback response
    return "Service temporarily unavailable";
}
```

### 6.3. Luồng Hoạt Động

#### 6.3.1. Circuit Breaker States

```
┌─────────────────────────────────────────────────────────────┐
│                    CLOSED State                              │
│  - Normal operation                                          │
│  - Calls Azure API                                           │
│  - Track success/failure rate                                │
└────────────────────────┬────────────────────────────────────┘
                         │
         Failure rate > 50% (5 failures / 10 calls)
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    OPEN State                                │
│  - Azure service is down                                    │
│  - Reject all requests immediately                          │
│  - Return fallback response                                 │
│  - Wait 60 seconds                                          │
└────────────────────────┬────────────────────────────────────┘
                         │
          After 60 seconds
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  HALF_OPEN State                            │
│  - Test Azure service                                       │
│  - Allow 3 test calls                                        │
│  - If all succeed → CLOSED                                  │
│  - If any fail → OPEN                                       │
└─────────────────────────────────────────────────────────────┘
```

#### 6.3.2. Request Flow

```
1. Client POST /api/files/upload/sas-url
   │
   ▼
2. Backend: AzureSasService.generateBlobReadSas()
   │
   ▼
3. Circuit Breaker: Check state
   │
   ├─ CLOSED:
   │  │
   │  ▼
   │  4. Call Azure API
   │     │
   │     ├─ Success → Return SAS URL
   │     │
   │     └─ Failure → Increment failure count
   │        │
   │        └─ If failure rate > 50% → OPEN
   │
   ├─ OPEN:
   │  │
   │  ▼
   │  4. Return fallback immediately (không call Azure)
   │     Response: "Service temporarily unavailable"
   │
   └─ HALF_OPEN:
      │
      ▼
      4. Test call Azure API
         │
         ├─ Success → CLOSED
         │
         └─ Failure → OPEN
```

### 6.4. Tại Sao Dùng Circuit Breaker?

- **Fault Tolerance:** Khi Azure service down, không spam requests → Giảm load
- **Fast Failure:** Return fallback ngay lập tức → Không đợi timeout
- **Auto Recovery:** Tự động test lại sau 60s → Không cần manual intervention

### 6.5. Lưu Ý: Circuit Breaker KHÔNG Distributed

- **In-Memory:** Mỗi backend instance có Circuit Breaker riêng
- **Không share state:** Backend 1 có thể OPEN, Backend 2 vẫn CLOSED
- **Lý do:** Circuit Breaker chỉ bảo vệ local instance, không cần share state

---

## 7. WebSocket Real-time Communication

### 7.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT (Browser)                          │
│  WebSocket: ws://localhost:80/ws                              │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              NGINX Load Balancer                            │
│  location /ws { proxy_pass http://backend; }                │
└────────────┬──────────────┬──────────────┬──────────────────┘
             │              │              │
      ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
      │  Backend 1  │ │  Backend 2  │ │  Backend 3  │
      │             │ │             │ │             │
      │ WebSocket   │ │ WebSocket   │ │ WebSocket   │
      │ Connection  │ │ Connection  │ │ Connection  │
      └─────────────┘ └─────────────┘ └─────────────┘
```

### 7.2. Cấu Hình

**File:** `src/main/java/com/example/valetkey/config/WebSocketConfig.java`

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }
    
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins("http://localhost:3000")
                .withSockJS();
    }
}
```

### 7.3. Luồng Hoạt Động

```
1. Client: Connect WebSocket
   ws://localhost:80/ws
   │
   ▼
2. Nginx: Route to Backend 1 (least_conn)
   │
   ▼
3. Backend 1: Accept WebSocket connection
   Connection ID: ws-123
   │
   ▼
4. Client: Subscribe to /topic/upload-progress/{uploadId}
   │
   ▼
5. Backend 1: Upload file → Send progress
   /topic/upload-progress/abc123
   Message: {progress: 50%, bytesUploaded: 500MB}
   │
   ▼
6. Client: Receive progress update
```

### 7.4. Lưu Ý: WebSocket KHÔNG Distributed

- **Sticky Connection:** Một WebSocket connection chỉ kết nối với 1 backend
- **Không share state:** Backend 1 không biết WebSocket connections của Backend 2
- **Giải pháp:** Nếu cần share WebSocket state, dùng Redis Pub/Sub hoặc external message broker (RabbitMQ, Kafka)

---

## 8. MySQL Database

### 8.1. Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│              Backend 1, 2, 3                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  JPA/Hibernate                                         │  │
│  │  Connection Pool: 8 connections per backend           │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────┬──────────────┬──────────────┬──────────────────┘
             │              │              │
             └──────────────┼──────────────┘
                            │
                    ┌───────▼────────┐
                    │   MYSQL DB      │
                    │   :3306         │
                    │                 │
                    │  Tables:        │
                    │  - users        │
                    │  - files        │
                    │  - folders      │
                    │  - ...          │
                    └─────────────────┘
```

### 8.2. Connection Pooling

**File:** `src/main/resources/application.properties`

```properties
spring.datasource.hikari.maximum-pool-size=8
spring.datasource.hikari.minimum-idle=2
```

**Tổng connections:**
- Backend 1: 8 connections
- Backend 2: 8 connections
- Backend 3: 8 connections
- **Total: 24 connections** (có thể tăng nếu cần)

### 8.3. Transaction Management

- **ACID:** MySQL đảm bảo ACID properties
- **Isolation Level:** READ_COMMITTED (default)
- **Deadlock Handling:** Hibernate tự động retry

---

## 9. Luồng Hoạt Động Tổng Thể

### 9.1. Complete Request Flow (Login)

```
┌─────────────────────────────────────────────────────────────┐
│  CLIENT: POST /login {username, password}                  │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  NGINX (Port 80)                                             │
│  1. Rate Limit Check (Nginx limit_req)                      │
│  2. Load Balance → Backend 1 (least_conn)                  │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  BACKEND 1                                                   │
│  1. RateLimitInterceptor: Check Bucket4j rate limit         │
│     - Key: "ip:192.168.1.100:LOGIN"                         │
│     - Redis: Check bucket tokens                            │
│  2. AuthController.login()                                  │
│     - Authenticate user                                      │
│     - Create SecurityContext                                 │
│     - Save to HttpSession                                    │
│  3. Spring Session: Serialize session → Redis              │
│     - Key: "spring:session:sessions:abc123"                │
│  4. Set-Cookie: SESSION=abc123                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                    ┌────▼────────┐
                    │     REDIS    │
                    │  - Session   │
                    │  - Rate Limit│
                    └──────────────┘
```

### 9.2. Complete Request Flow (List Files)

```
┌─────────────────────────────────────────────────────────────┐
│  CLIENT: GET /api/files/list?folderId=1                      │
│  Cookie: SESSION=abc123                                      │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  NGINX (Port 80)                                             │
│  1. Rate Limit Check (Nginx limit_req)                      │
│  2. Load Balance → Backend 2 (least_conn)                   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  BACKEND 2                                                   │
│  1. RateLimitInterceptor: Check Bucket4j rate limit         │
│     - Key: "user:123:LIST_FILES" (nếu authenticated)        │
│     - Redis: Check bucket tokens                            │
│  2. Spring Session: Read session from Redis                 │
│     - Key: "spring:session:sessions:abc123"                  │
│     - Deserialize → SecurityContext                          │
│  3. Security: Check authentication                            │
│  4. FileController.listFiles(folderId=1)                    │
│     - @Cacheable("fileList")                                │
│     - Check Redis Cache: "cache:fileList::1"                │
│       ├─ HIT → Return cached data                           │
│       └─ MISS → Query MySQL → Save cache → Return          │
└────────────────────────┬────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
    ┌────▼────┐     ┌────▼────┐     ┌────▼────┐
    │  REDIS  │     │  MYSQL  │     │  CACHE  │
    │ Session │     │   DB    │     │  Redis  │
    └─────────┘     └─────────┘     └─────────┘
```

---

## 10. Tương Tác Giữa Các Components

### 10.1. Redis: Central Hub

```
┌─────────────────────────────────────────────────────────────┐
│                        REDIS                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Database 0: Spring Session                          │  │
│  │  Key: spring:session:sessions:*                       │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Database 0: Bucket4j Rate Limiting                   │  │
│  │  Key: user:*:*, ip:*:*, token:*:*                     │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Database 0: Redis Cache                              │  │
│  │  Key: cache:fileList:*, cache:fileMetadata:*         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 10.2. Data Flow Diagram

```
                    CLIENT
                       │
                       ▼
                  ┌─────────┐
                  │  NGINX  │
                  │ (Port 80)│
                  └────┬────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │Backend 1│    │Backend 2│    │Backend 3│
   └────┬────┘    └────┬────┘    └────┬────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │  REDIS  │    │  MYSQL  │    │  AZURE  │
   │ (Shared)│    │  (Shared)│    │  BLOB   │
   └─────────┘    └─────────┘    └─────────┘
```

### 10.3. Component Dependencies

| Component | Depends On | Purpose |
|-----------|------------|---------|
| **Nginx** | Backend 1, 2, 3 | Load balancing, rate limiting (first layer) |
| **Backend** | Redis, MySQL, Azure | Application logic |
| **Spring Session** | Redis | Session storage |
| **Bucket4j** | Redis | Rate limiting state |
| **Redis Cache** | Redis | Cache storage |
| **Circuit Breaker** | None (in-memory) | Fault tolerance |
| **WebSocket** | None (per instance) | Real-time communication |

---

## 11. Best Practices & Lưu Ý

### 11.1. Redis Connection Pooling

- **Lettuce Connection Pool:** Max 8 connections per backend
- **Total:** 3 backends × 8 connections = 24 connections to Redis
- **Tối ưu:** Đủ cho load hiện tại, có thể tăng nếu cần

### 11.2. Session TTL

- **Session Timeout:** 30 phút (1800 seconds)
- **Redis TTL:** Tự động xóa session sau 30 phút không hoạt động
- **Cookie Max-Age:** 30 phút

### 11.3. Rate Limiting Strategy

- **2 Layers:**
  1. **Nginx `limit_req`:** Fast, chặn ở tầng Nginx
  2. **Bucket4j:** Flexible, có thể rate limit theo user ID, IP, token

### 11.4. Cache Strategy

- **TTL ngắn:** `fileList` = 1 phút (data thay đổi thường xuyên)
- **TTL dài:** `fileMetadata` = 15 phút (data ít thay đổi)
- **Cache Invalidation:** Dùng `@CacheEvict` khi update data

### 11.5. Load Balancing

- **Strategy:** `least_conn` (chọn backend có ít connections nhất)
- **Không cần sticky session:** Vì session được share qua Redis
- **Health Check:** Nginx tự động loại bỏ backend down

---

## 12. Monitoring & Debugging

### 12.1. Redis Keys

```bash
# Xem tất cả session keys
redis-cli KEYS "spring:session:*"

# Xem tất cả rate limit buckets
redis-cli KEYS "user:*" "ip:*" "token:*"

# Xem tất cả cache keys
redis-cli KEYS "cache:*"
```

### 12.2. Backend Logs

```bash
# Xem logs của tất cả backends
docker logs -f valet-key-backend-1
docker logs -f valet-key-backend-2
docker logs -f valet-key-backend-3
```

### 12.3. Nginx Logs

```bash
# Xem access logs
docker logs -f valet-key-nginx

# Xem error logs
docker exec valet-key-nginx tail -f /var/log/nginx/error.log
```

### 12.4. Health Endpoints

- **Spring Actuator:** `http://localhost/actuator/health`
- **Circuit Breaker Status:** `http://localhost/actuator/circuitbreakers`
- **Rate Limit Stats:** `http://localhost/admin/monitoring/rate-limits/user/{userId}`

---

## Kết Luận

Project này sử dụng **7 distributed systems/components** chính:

1. ✅ **Nginx Load Balancer** - Phân phối requests
2. ✅ **Spring Session + Redis** - Shared session management
3. ✅ **Bucket4j + Redis** - Distributed rate limiting
4. ✅ **Redis Cache** - Shared caching
5. ⚠️ **Circuit Breaker** - In-memory (per instance)
6. ⚠️ **WebSocket** - Per instance (không distributed)
7. ✅ **MySQL Database** - Shared database

**Tất cả các components này hoạt động cùng nhau để tạo ra một hệ thống phân tán, scalable, và fault-tolerant.**


