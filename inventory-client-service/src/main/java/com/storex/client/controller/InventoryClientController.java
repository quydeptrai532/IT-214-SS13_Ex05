package com.storex.client.controller;
import com.storex.client.dto.InventoryDto;
import com.storex.client.service.InventoryClientService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/client")
public class InventoryClientController {

    private final InventoryClientService inventoryClientService;

    public InventoryClientController(InventoryClientService inventoryClientService) {
        this.inventoryClientService = inventoryClientService;
    }

    /** Non-blocking 100%: tra ve Mono truc tiep, khong block */
    @GetMapping("/inventory/check")
    public Mono<InventoryDto> check(@RequestParam(defaultValue = "SKU-1") String sku) {
        return inventoryClientService.checkInventory(sku);
    }
}
