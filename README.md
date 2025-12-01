# Valet Key - Hệ Thống Quản Lý File Đám Mây

## Tổng Quan

Valet Key là một hệ thống quản lý file đám mây, tích hợp Azure Blob Storage để lưu trữ file và cung cấp các tính năng bảo mật, hiệu năng cao với khả năng mở rộng tốt.

## Kiến Trúc Hệ Thống

### Load Balancing

Hệ thống sử dụng **Nginx** làm reverse proxy và load balancer với thuật toán **least_conn** (least connection):

- **Backend instances**: Hệ thống hỗ trợ nhiều backend instances (backend1, backend2, backend3) chạy song song
- **Load balancing strategy**: Nginx phân phối request đến backend instance có số kết nối ít nhất
### Session Management

Hệ thống sử dụng **Spring Session Redis** để quản lý session:

- **Session store**: Session được lưu trữ trong Redis, cho phép chia sẻ session giữa các backend instances
- **Session timeout**: 1800 giây (30 phút)
- **Session namespace**: `spring:session` trong Redis
## Rate Limiting Strategy

Hệ thống triển khai **hai lớp bảo vệ rate limiting** để chống DDoS và abuse:

### Lớp 1: Nginx (IP-based Protection)

Nginx áp dụng rate limiting dựa trên IP address để bảo vệ tổng thể hệ thống:


### Lớp 2: Bucket4j 

Bucket4j sử dụng Redis để lưu trữ rate limit buckets theo user/session:

#### Phân loại Rate Limits:

**Authentication**
- `LOGIN`: 5 requests/phút per IP

**File Write Operations**
- `FILE_WRITE`: 30 requests/phút per user (upload, delete, move, rename)
- `FILE_SHARE`: 20 requests/phút per user (share/unshare)

**Bulk Operations **
- `BULK_OPERATION`: 5 requests/phút per user (bulk delete/move)
- `BULK_DOWNLOAD`: 10 requests/phút per user (ZIP creation)

**Folder Operations**
- `FOLDER_WRITE`: 30 requests/phút per user (create, update)
- `FOLDER_DELETE`: 20 requests/phút per user (delete với cascade)

**Download**
- `DOWNLOAD`: 100 requests/phút per user (cached SAS URLs)

**Public Access**
- `PUBLIC_ACCESS_IP`: 30 requests/phút per IP
- `PUBLIC_DOWNLOAD_TOKEN`: 100 requests/giờ per token

**Admin Operations**
- `ADMIN_WRITE`: 10 requests/phút per admin
- `ADMIN_CLEAR`: 5 requests/phút per admin

#### Read-only Operations (No Rate Limit)**

Các endpoint read-only không bị rate limit để đảm bảo UX tốt:
- Folder tree, breadcrumb, list
- File list, metadata, search
- Storage info
- User info (`/user`)
- Admin monitoring (GET requests)


## Caching Strategy

Hệ thống sử dụng **Redis Cache** với các TTL (Time To Live) khác nhau tùy theo đặc tính của dữ liệu:

### Cache Types và TTL:

1. **sasUrls** (9 phút)
   - Cache SAS URLs để giảm số lần gọi Azure API
   - TTL ngắn hơn expiry time của SAS (10 phút) để đảm bảo tính hợp lệ

2. **fileMetadata** (15 phút)
   - Metadata của file (tên, kích thước, contentType, etc.)
   - Dữ liệu ít thay đổi sau khi upload

3. **userStorage** (1 phút)
   - Thông tin storage của user (dung lượng đã dùng, quota)
   - TTL ngắn để đảm bảo accuracy cho quota enforcement

4. **folderTree** (5 phút)
   - Cây thư mục của user
   - Thay đổi ít thường xuyên hơn file operations




## Circuit Breaker và Retry

Hệ thống sử dụng **Resilience4j** để xử lý lỗi và tăng độ tin cậy:

### Circuit Breaker Configuration:

**Azure Service Circuit Breaker:**
- **Sliding window size**: 10 requests
- **Minimum calls**: 5 calls trước khi tính failure rate
- **Failure rate threshold**: 50%
- **Slow call rate threshold**: 50%
- **Slow call duration**: 10 giây
- **Wait duration in open state**: 60 giây
- **Half-open state**: Cho phép 3 calls để test recovery
- **Automatic transition**: Tự động chuyển từ OPEN sang HALF_OPEN

**States:**
- **CLOSED**: Hoạt động bình thường
- **OPEN**: Azure unavailable, trả về fallback ngay lập tức
- **HALF_OPEN**: Đang test recovery, cho phép một số calls

**Fallback Methods:**
- `generateBlobReadSasFallback`: Trả về error message khi không thể generate SAS
- `deleteBlobFallback`: Log error và throw exception khi không thể delete

### Retry Configuration:

**Azure Service Retry:**
- **Max attempts**: 3 lần
- **Wait duration**: 2 giây
- **Exponential backoff**: Enabled với multiplier=4
  - Lần 1: 2 giây
  - Lần 2: 8 giây (2 × 4)
- **Retry exceptions**: Tất cả Exception (trừ IllegalArgumentException)


## Monitoring và Observability

Hệ thống cung cấp các endpoints monitoring để theo dõi sức khỏe và hiệu năng:

### Monitoring Endpoints (`/api/admin/monitoring`):

1. **Circuit Breaker Status** (`GET /circuit-breakers`)
   - Trạng thái của tất cả circuit breakers
   - Metrics: state, failure rate, số lượng calls (successful/failed/buffered/not permitted)

2. **Retry Metrics** (`GET /retries`)
   - Metrics về retry operations
   - Số lượng calls thành công/có retry, số lượng calls failed

3. **Health Summary** (`GET /health-summary`)
   - Tổng quan sức khỏe hệ thống
   - Số lượng circuit breakers đang OPEN
   - Tổng số caches
   - Overall status: HEALTHY hoặc DEGRADED

### Spring Boot Actuator:

Hệ thống expose các actuator endpoints:
- `/actuator/health`: Health check
- `/actuator/metrics`: Metrics
- `/actuator/prometheus`: Prometheus metrics
- `/actuator/circuitbreakers`: Circuit breaker metrics
- `/actuator/ratelimiters`: Rate limiter metrics


## Redis Configuration

Redis được sử dụng cho nhiều mục đích:

### 1. Session Storage (Spring Session)
- **Store type**: Redis
- **Namespace**: `spring:session`
- **Flush mode**: Immediate

### 2. Cache Storage
- **Cache type**: Redis
- **Key prefix**: `cache:`
- **TTL**: Tùy theo cache type

### 3. Rate Limiting (Bucket4j)
- **Proxy manager**: LettuceBasedProxyManager


## File Upload Flow

1. **Client request SAS URL** → Backend generate write SAS URL
2. **Client upload trực tiếp lên Azure** → Sử dụng SAS URL
3. **Client confirm upload** → Backend lưu metadata vào database
4. **File available** → User có thể download/share



## Performance Optimizations

1. **Caching**: Giảm số lần query database và gọi Azure API
3. **Batch operations**: Hibernate batch size=20 cho bulk operations
4. **Direct Azure upload**: Giảm load trên backend
5. **Load balancing**: Phân phối request đều giữa các backend instances
6. **Session sharing**: Redis session cho phép horizontal scaling

## Scalability

Hệ thống được thiết kế để scale horizontally:

- **Multiple backend instances**: Có thể thêm/bớt backend instances
- **Shared session storage**: Redis session cho phép stateless backends
- **Shared cache**: Redis cache được share giữa tất cả instances
- **Shared rate limiting**: Bucket4j với Redis cho distributed rate limiting
- **Load balancer**: Nginx phân phối request tự động

