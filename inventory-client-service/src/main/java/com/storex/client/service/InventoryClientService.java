package com.storex.client.service;
import com.storex.client.dto.InventoryDto;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * CAPSTONE: WebFlux + Circuit Breaker KHONG dung annotation.
 *
 * Dung .transformDeferred(CircuitBreakerOperator.of(circuitBreaker)) de boc luong Mono
 * => giu nguyen 100% tinh chat non-blocking, KHONG co .block() o bat ky dau.
 */
@Service
public class InventoryClientService {
    private static final Logger log = LoggerFactory.getLogger(InventoryClientService.class);

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
                // ★ Gan Circuit Breaker ngay trong chuoi Reactive (khong dung AOP/@CircuitBreaker)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // ★ Fallback khi mach OPEN hoac service loi
                .onErrorResume(ex -> {
                    log.warn("[FALLBACK] Khong goi duoc inventory-service ({}). Tra du lieu mac dinh.",
                            ex.getClass().getSimpleName());
                    return Mono.just(new InventoryDto(sku, false, 0, "FALLBACK"));
                });
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
