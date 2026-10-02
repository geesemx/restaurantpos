package mx.restaurante.pos.model;
public record Product(String sku, String name, String category, double price, int stock, String supplier) { }
