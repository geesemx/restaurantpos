package mx.restaurante.pos.model;

import java.time.LocalDateTime;

public record Refund(String id, String saleId, String sku, int quantity, double amount, LocalDateTime date) { }
