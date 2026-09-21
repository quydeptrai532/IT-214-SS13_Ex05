package com.storex.client;
import com.storex.client.dto.InventoryDto;
import com.storex.client.service.InventoryClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryClientCapstoneTest {

    static final MockWebServer inventoryServer = new MockWebServer();

    static {
        try {
            inventoryServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("inventory.base-url", () -> inventoryServer.url("/").toString());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private InventoryClientService inventoryClientService;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private void mockInventory(int code, String body) {
        inventoryServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(code)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body);
            }
        });
    }

    private String metric(String tags) {
        return new String(client().get()
                .uri("/actuator/metrics/resilience4j.circuitbreaker.state?" + tags)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody());
    }

    @Test
    void goiThanhCongTraDuLieuTuInventoryService() {
        CircuitBreaker cb = inventoryClientService.getCircuitBreaker();
        cb.reset();
        mockInventory(200, "{\"sku\":\"SKU-1\",\"available\":true,\"quantity\":10,\"source\":\"INVENTORY-SERVICE\"}");

        InventoryDto dto = client().get().uri("/api/client/inventory/check?sku=SKU-1")
                .exchange().expectStatus().isOk()
                .expectBody(InventoryDto.class).returnResult().getResponseBody();

        System.out.println(">>> [SUCCESS] Ket qua tu inventory-service: " + dto);
        assertThat(dto).isNotNull();
        assertThat(dto.getSource()).isEqualTo("INVENTORY-SERVICE");
        assertThat(dto.isAvailable()).isTrue();
    }

    @Test
    void circuitBreakerMoVaTraFallbackKhiServiceLoi() {
        CircuitBreaker cb = inventoryClientService.getCircuitBreaker();
        cb.reset();
        mockInventory(500, "{\"error\":\"boom\"}");

        InventoryDto last = null;
        for (int i = 0; i < 10; i++) {
            last = client().get().uri("/api/client/inventory/check?sku=SKU-2")
                    .exchange().expectStatus().isOk()
                    .expectBody(InventoryDto.class).returnResult().getResponseBody();
        }

        System.out.println(">>> [FALLBACK] Ket qua cuoi=" + last + " | CB state=" + cb.getState()
                + " | failureRate=" + cb.getMetrics().getFailureRate()
                + " | failedCalls=" + cb.getMetrics().getNumberOfFailedCalls());
        assertThat(last.getSource()).isEqualTo("FALLBACK");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void actuatorMetricHienThiTagStateDungThucTe() {
        CircuitBreaker cb = inventoryClientService.getCircuitBreaker();
        cb.reset();
        mockInventory(200, "{\"sku\":\"SKU-3\",\"available\":true,\"quantity\":5,\"source\":\"INVENTORY-SERVICE\"}");
        client().get().uri("/api/client/inventory/check?sku=SKU-3").exchange().expectStatus().isOk();

        String closedBody = metric("tag=name:inventoryService&tag=state:closed");
        String openBody = metric("tag=name:inventoryService&tag=state:open");
        System.out.println(">>> [ACTUATOR] state=closed -> " + closedBody);
        System.out.println(">>> [ACTUATOR] state=open   -> " + openBody);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(closedBody).contains("1.0");
        assertThat(openBody).contains("0.0");
        assertThat(closedBody).contains("resilience4j.circuitbreaker.state");
    }
}
