# BÀI TẬP 5 (SS13) — CAPSTONE: WEBFLUX + CIRCUIT BREAKER + MICROMETER METRICS

**Yêu cầu của kiến trúc sư:** *"Chúng ta đang dùng WebFlux (Non-blocking). Nếu dùng `@CircuitBreaker` kiểu cũ (AOP-based), có nguy cơ nó sẽ cản trở luồng Reactive. Hãy dùng `WebClient` kết hợp với `CircuitBreakerOperator` của Resilience4j để đảm bảo Non-blocking 100%. Đồng thời phải xuất được dữ liệu ra cổng `/actuator/metrics` để đội vận hành gắn lên Grafana."*

---

## 1. VÌ SAO KHÔNG DÙNG `@CircuitBreaker` TRONG LUỒNG REACTIVE?

| | `@CircuitBreaker` (AOP-based) | `CircuitBreakerOperator` (Reactive) |
|---|---|---|
| Cơ chế | Spring AOP tạo **proxy bao quanh method** | **Toán tử trong chuỗi `Mono`/`Flux`** |
| Bản chất luồng | Chặn ở tầng method call (blocking-style) | Chạy hoàn toàn trong chuỗi Reactive |
| Với WebFlux | Dễ chặn/che khuất luồng non-blocking; khó kiểm soát khi nào `Mono` được subscribe | `transformDeferred` chỉ áp dụng **tại thời điểm subscribe** ⇒ đúng ngữ nghĩa Reactive |
| Khả năng kết hợp | Khó kết hợp với `retryWhen`, `timeout`, `onErrorResume` | Kết hợp tự nhiên bằng `Mono` operators |

**`transformDeferred` khác `transform` ở đâu?** `transform` áp dụng **ngay khi dựng chuỗi** (một lần), còn `transformDeferred` **trì hoãn** việc áp dụng cho tới lúc có subscriber — nên Circuit Breaker được "gắn động" cho từng lần subscribe, đúng với vòng đời Reactive.

---

## 2. TRIỂN KHAI

### 2.1. Khởi tạo WebClient

```java
@Bean
public WebClient inventoryWebClient(@Value("${inventory.base-url}") String baseUrl) {
    HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(2))
            .doOnConnected(c -> c.addHandlerLast(new ReadTimeoutHandler(2, TimeUnit.SECONDS)));
    return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
}
```

### 2.2. Luồng Reactive + CircuitBreakerOperator (KHÔNG annotation)

```java
@Service
public class InventoryClientService {

    public static final String CB_NAME = "inventoryService";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;

    public InventoryClientService(WebClient inventoryWebClient, CircuitBreakerRegistry registry) {
        this.webClient = inventoryWebClient;
        this.circuitBreaker = registry.circuitBreaker(CB_NAME);
    }

    public Mono<InventoryDto> checkInventory(String sku) {
        return webClient.get()
                .uri("/api/inventory/check")
                .retrieve()
                .bodyToMono(InventoryDto.class)
                // ★ Gắn Circuit Breaker trong chuỗi Reactive
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // ★ Fallback khi mạch OPEN hoặc service lỗi
                .onErrorResume(ex -> Mono.just(
                        new InventoryDto(sku, false, 0, "FALLBACK")));
    }
}
```

Luồng dữ liệu (đúng như hình đề bài):

```
webClient.get()
    .uri("/api/inventory/check")
    .retrieve()
    .bodyToMono(InventoryDTO.class)
    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))   // Gan Circuit Breaker tai day
    .onErrorResume(...)                                             // Gan Fallback tai day
```

### 2.3. Dependency cần thiết

```gradle
implementation 'org.springframework.boot:spring-boot-starter-webflux'
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j'
implementation 'io.github.resilience4j:resilience4j-micrometer'   // ★ xuat metric
implementation 'io.github.resilience4j:resilience4j-reactor'      // ★ CircuitBreakerOperator
```

### 2.4. Bật phơi bày Actuator (`application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 6
        minimumNumberOfCalls: 3
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        registerHealthIndicator: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers,circuitbreakerevents,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      application: inventory-client-service
  health:
    circuitbreakers:
      enabled: true
```

---

## 3. KẾT QUẢ KIỂM CHỨNG (chạy thật)

Test `InventoryClientCapstoneTest` — xem `Ex05_TestEvidence.txt`.

### Checklist 1 — không có `.block()` trong mã nguồn

Đã grep toàn bộ `src/main`: **không** tìm thấy `.block()`, `.toFuture()` hay `@CircuitBreaker`.
```
(chi co 2 dong khop la COMMENT giai thich, khong phai code)
```

### Checklist 2 — Actuator metric hiển thị đúng tags

```
>>> [ACTUATOR] state=closed -> {"description":"The states of the circuit breaker",
        "measurements":[{"statistic":"VALUE","value":1.0}],
        "name":"resilience4j.circuitbreaker.state"}

>>> [ACTUATOR] state=open   -> {"measurements":[{"statistic":"VALUE","value":0.0}],
        "name":"resilience4j.circuitbreaker.state"}
```

✅ Khi mạch **CLOSED**: `state=closed` → **VALUE = 1.0**, `state=open` → **VALUE = 0.0** — đúng yêu cầu checklist và đủ dữ liệu để Grafana vẽ biểu đồ.

**Cách gọi đúng (phải truyền tag, nếu không Actuator chỉ trả `availableTags`):**
```
http://localhost:8094/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:inventoryService&tag=state:closed
```

### Kiểm chứng Circuit Breaker hoạt động

```
>>> [SUCCESS]  Ket qua tu inventory-service: InventoryDto(sku=SKU-1, available=true, quantity=10, source=INVENTORY-SERVICE)
>>> [FALLBACK] Khong goi duoc inventory-service (InternalServerError)...
>>> [FALLBACK] Khong goi duoc inventory-service (CallNotPermittedException)...
>>> [FALLBACK] Ket qua cuoi=InventoryDto(sku=SKU-2, ..., source=FALLBACK)
    | CB state=OPEN | failureRate=100.0 | failedCalls=3
```

- Gọi thành công → dữ liệu thật từ inventory-service.
- inventory-service lỗi 500 liên tục → **mạch OPEN** (failureRate 100%), các request sau bị `CallNotPermittedException` và trả **FALLBACK** ⇒ hệ thống **không sập theo** service lỗi.

---

## 4. TỔNG KẾT

| Yêu cầu | Kết quả |
|---|---|
| Dùng WebClient gọi `/api/inventory/check` | ✅ |
| Không dùng annotation, dùng `transformDeferred(CircuitBreakerOperator.of(cb))` | ✅ |
| Thêm `resilience4j-micrometer` + `actuator`, expose endpoint | ✅ |
| Không có `.block()` trong mã nguồn | ✅ (đã grep) |
| `/actuator/metrics/resilience4j.circuitbreaker.state` hiển thị tags đúng | ✅ (closed=1.0, open=0.0) |
