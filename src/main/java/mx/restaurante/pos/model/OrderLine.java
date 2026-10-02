package mx.restaurante.pos.model;

import java.time.LocalDateTime;

public record OrderLine(String id, String orderId, int tableNumber, String sku, String productName, int quantity,
                        String modifiers, String kitchenStatus, LocalDateTime createdAt) { }
