## Bucket4j + Redis Rate Limiting Guide

This document walks through every step needed to implement the existing Bucket4j + Redis rate limiting stack in this project. It is written for beginners—follow each section in order and you will end up with a working, horizontally scalable rate limiter that protects all backend instances equally.

---

### 1. Why we use Bucket4j + Redis
- **Problem we solve:** Without a limiter, a single user (or attacker) can spam expensive APIs (login, upload, search, etc.) and overload the cluster.
- **Bucket4j solves the algorithm:** It gives us the Token Bucket algorithm (capacity + refill rate) with a simple Java API.
- **Redis stores shared state:** All backend containers need to “see” the same remaining tokens. Bucket4j’s Redis integration stores the bucket state in Redis so every instance enforces the same limits.

Result: regardless of which backend instance handles the request (even behind Nginx load balancing), it reads the same bucket in Redis and applies the same throttle decisions.

---

### 2. Prerequisites
1. **Redis server** – already running via Docker Compose (`redis` service). Confirm it is reachable at `spring.data.redis.*` settings.
2. **Maven dependencies** – `pom.xml` already includes:
   ```xml
   <dependency>
       <groupId>com.github.vladimir-bukhtoyarov</groupId>
       <artifactId>bucket4j-core</artifactId>
       <version>8.5.0</version>
   </dependency>
   <dependency>
       <groupId>com.github.vladimir-bukhtoyarov</groupId>
       <artifactId>bucket4j-redis</artifactId>
       <version>8.5.0</version>
   </dependency>
   ```
3. **Spring Session + Redis** – already configured inside `RedisConfig`. This is separate from rate limiting, but it ensures user sessions are also shared.

---

### 3. Configure Redis access for Bucket4j
File: `src/main/java/com/example/valetkey/config/RedisConfig.java`

1. **Create a dedicated Lettuce `RedisClient`** for Bucket4j so we can use the CAS (compare-and-swap) API that Bucket4j expects:
   ```java
   @Bean
   public RedisClient bucket4jRedisClient() {
       RedisURI.Builder uriBuilder = RedisURI.builder()
               .withHost(redisHost)
               .withPort(redisPort)
               .withDatabase(redisDatabase);
       if (!redisPassword.isEmpty()) {
           uriBuilder.withPassword(redisPassword.toCharArray());
       }
       return RedisClient.create(uriBuilder.build());
   }
   ```
2. **Open a binary-friendly connection** (`StatefulRedisConnection<String, byte[]>`) because Bucket4j stores serialized buckets as byte arrays.
3. **Expose a `LettuceBasedProxyManager<String>` bean**:
   ```java
   @Bean
   public LettuceBasedProxyManager<String> bucket4jProxyManager(
           StatefulRedisConnection<String, byte[]> connection) {
       return LettuceBasedProxyManager.builderFor(connection)
               .withExpirationStrategy(
                   ExpirationAfterWriteStrategy
                       .basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(24)))
               .build();
   }
   ```
   The expiration strategy ensures Redis keys disappear if they are not touched after 24h, keeping the DB clean.

Why this matters: Bucket4j uses the proxy manager to read/write bucket state atomically in Redis. Without it, each instance would keep its own in-memory bucket and rate limits would be inconsistent.

---

### 4. Centralize bucket logic in `RateLimitService`
File: `src/main/java/com/example/valetkey/service/RateLimitService.java`

Steps already implemented here:

1. **Inject the proxy manager** and `RedisTemplate` (for admin utilities like clearing keys).
2. **Declare `RateLimitType` enum**: each entry defines a capacity and refill duration. Example:
   ```java
   LOGIN(5, Duration.ofMinutes(15));
   ```
   This reads as “5 login attempts every 15 minutes per key”.
3. **Build a `BucketConfiguration`** dynamically for each type. Bucket4j requires us to specify:
   - `capacity`: total tokens inside the bucket.
   - `refillGreedy(capacity, refillDuration)`: refill the bucket back to full over the specified window.
4. **Resolve a bucket per key**:
   ```java
   Bucket bucket = proxyManager.builder().build(key, configurationSupplier);
   ```
   The proxy manager talks to Redis. If the key does not exist, it creates a new bucket with the supplied configuration; otherwise, it reuses the existing state.
5. **Expose helper methods**:
   - `tryConsume(key, type)` → returns `true` if the request is allowed.
   - `getAvailableTokens(key, type)` → used to emit response headers.
   - `generateUserKey`, `generateIpKey`, `generateTokenKey` → standardize key formats (`user:42:UPLOAD_SMALL`, etc.).
   - `clearBucket`, `clearAllBuckets`, `getBucketStats` → admin/monitoring APIs.

Why centralize: Keeping all bucket math in one service makes it trivial to adjust limits later or add new endpoint categories.

---

### 5. Build an HTTP interceptor to enforce limits
File: `src/main/java/com/example/valetkey/interceptor/RateLimitInterceptor.java`

1. **Hook into Spring MVC** with `HandlerInterceptor#preHandle`. We run before the controller executes.
2. **Map request → `RateLimitType`:**
   ```java
   if (uri.equals("/login") && method.equals("POST")) {
       return RateLimitType.LOGIN;
   }
   if (uri.equals("/api/files/list") && method.equals("GET")) {
       return RateLimitType.LIST_FILES;
   }
   ```
   The current switch covers login, SAS URL generation, bulk operations, downloads, public links, search, and file listing. Add new cases here when new endpoints need throttling.
3. **Determine the key**:
   - For public endpoints or login: use client IP (`X-Forwarded-For` → `X-Real-IP` → `request.getRemoteAddr()`).
   - For authenticated endpoints: prefer the `User` object stored in session (`user.getId()`), fallback to IP.
4. **Call `rateLimitService.tryConsume(key, type)`**:
   - If it returns `false`, respond with HTTP 429 and a JSON error message.
   - Otherwise, add helpful headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`) so clients know how close they are to the cap.
5. **Register the interceptor** in `WebMvcConfig`:
   ```java
   registry.addInterceptor(rateLimitInterceptor)
           .addPathPatterns("/**")
           .excludePathPatterns("/actuator/**", "/ws/**", "/error");
   ```

Why use an interceptor (instead of a controller annotation): we only have to write the logic once, and it applies uniformly to every request. It also works nicely with Spring Session because the interceptor sees the resolved session principal.

---

### 6. Monitoring & admin utilities
File: `src/main/java/com/example/valetkey/controller/MonitoringController.java`

Endpoints already provided:
- `GET /admin/monitoring/rate-limits/user/{userId}` → shows available tokens per `RateLimitType`.
- `POST /admin/monitoring/rate-limits/clear-all` → wipes all buckets in Redis (useful for QA resets).

These endpoints call `RateLimitService.getBucketStats/clearAllBuckets`. Keep them behind ADMIN auth only.

---

### 7. Testing checklist
1. **Local smoke test (Postman/cURL):**
   - Send the same request more times than the configured capacity (e.g., 6 login attempts within 15 minutes). The 6th should return 429 with JSON `{ "error": "Rate limit exceeded", ... }`.
   - Check response headers for allowed requests: `X-RateLimit-Remaining` should decrease.
2. **Distributed test (k6 / JMeter):**
   - Spin up multiple backend containers (`backend1`, `backend2`) and hammer the same endpoint through Nginx.
   - Confirm logs on both instances show 429 after total combined requests exceed the limit. Because the bucket lives in Redis, the limiter should trip even when requests hop between servers.
3. **Redis key inspection:**
   ```bash
   docker exec -it valet_key_redis redis-cli KEYS "user:*"
   docker exec -it valet_key_redis redis-cli TTL "user:1:LIST_FILES"
   ```
   Keys should appear/disappear according to traffic and the 24h expiration strategy.

---

### 8. Extending the limiter
When new APIs arrive:
1. Decide **who to limit** (per user, per IP, per token).
2. Add a new `RateLimitType` entry, specify sensible capacity/refill.
3. Map the endpoint in `RateLimitInterceptor#determineRateLimitType`.

Because everything else (Redis storage, proxy manager, interceptor registration) is already in place, new limits only require editing the enum + the switch statement.

---

### 9. Troubleshooting tips
- **All requests always allowed:** ensure the new endpoint is actually mapped to a `RateLimitType`. If `determineRateLimitType` returns `null` the interceptor skips limiting.
- **Always hitting 429 immediately:** verify that the key being generated is not `null` and that the bucket capacity/refill values are correct. Use `MonitoringController` endpoints to inspect token counts.
- **Distributed instances not sharing limits:** confirm every backend points to the same Redis and that the `bucket4jProxyManager` bean is a singleton (default in Spring). Also check Redis network/firewall settings.
- **Need emergency reset:** call `POST /admin/monitoring/rate-limits/clear-all` or manually delete specific keys in Redis.

---

You now have a complete mental model and implementation path: RedisConfig wires the distributed bucket store, RateLimitService defines the policies, RateLimitInterceptor enforces them, and MonitoringController gives observability. Adjust the enum values and URI mappings to match business requirements, redeploy, and the entire cluster enforces the new rules instantly.


