package mx.restaurante.pos.model;

import java.time.LocalDateTime;

public record CashClosure(String id, String type, LocalDateTime from, LocalDateTime to,
                          double grossSales, double refunds, double netSales) { }
