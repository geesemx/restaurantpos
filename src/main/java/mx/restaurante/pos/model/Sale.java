package mx.restaurante.pos.model;
import java.time.LocalDateTime;
public record Sale(String id, String description, String sku, int quantity, double subtotal, double iva,
                   double ieps, double total, double paid, double change, LocalDateTime date) { }
