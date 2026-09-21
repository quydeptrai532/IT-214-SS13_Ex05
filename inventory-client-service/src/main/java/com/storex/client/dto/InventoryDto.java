package com.storex.client.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InventoryDto {
    private String sku;
    private boolean available;
    private int quantity;
    private String source;
}
