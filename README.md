# BÀI TẬP 5 (SS13) — CAPSTONE: WEBFLUX + CIRCUIT BREAKER + MICROMETER

Xem phân tích tại `Ex05_Analysis.md`, bằng chứng chạy thật tại `Ex05_TestEvidence.txt`.

## Cấu trúc

```
Ex05/
├── Ex05_Analysis.md
├── Ex05_TestEvidence.txt
├── README.md
└── inventory-client-service/
    ├── build.gradle
    └── src/main/
        ├── java/com/storex/client/
        │   ├── service/InventoryClientService.java  # ★ transformDeferred(CircuitBreakerOperator.of(cb))
        │   ├── config/WebClientConfig.java          # WebClient + timeout 2s
        │   ├── controller/InventoryClientController.java
        │   └── dto/InventoryDto.java
        └── resources/application.yml                # actuator expose + resilience4j config
```

## Chạy test

```bash
cd inventory-client-service
./gradlew test
```

## Kết quả

```
>>> [SUCCESS] Ket qua tu inventory-service: InventoryDto(sku=SKU-1, available=true, quantity=10, source=INVENTORY-SERVICE)
>>> [FALLBACK] Ket qua cuoi=InventoryDto(sku=SKU-2, available=false, quantity=0, source=FALLBACK)
    | CB state=OPEN | failureRate=100.0 | failedCalls=3
>>> [ACTUATOR] state=closed -> {...,"measurements":[{"statistic":"VALUE","value":1.0}],"name":"resilience4j.circuitbreaker.state"}
>>> [ACTUATOR] state=open   -> {...,"measurements":[{"statistic":"VALUE","value":0.0}],...}
```

## Chạy thật + kiểm tra Actuator

```bash
./gradlew bootRun      # port 8094

# Trang thai mach
curl "http://localhost:8094/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:inventoryService&tag=state:closed"
curl "http://localhost:8094/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:inventoryService&tag=state:open"

# Goi API
curl "http://localhost:8094/api/client/inventory/check?sku=SKU-1"
```

> **Lưu ý:** phải truyền `?tag=...` thì Actuator mới trả giá trị (`VALUE`);
> nếu gọi trần chỉ nhận được danh sách `availableTags`.
>
> Ghi chú dependency: cần thêm tường minh `resilience4j-micrometer` (xuất metric) và
> `resilience4j-reactor` (chứa `CircuitBreakerOperator`) — starter không kéo theo đủ.
>
> Mã nguồn **không chứa `.block()`** — đã kiểm tra bằng grep toàn bộ `src/main`.
a