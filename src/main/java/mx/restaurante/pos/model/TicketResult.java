package mx.restaurante.pos.model;

import java.util.List;

public record TicketResult(String id, List<Sale> lines, double subtotal, double iva, double ieps,
                           double total, double received, double change, List<Payment> payments) { }
