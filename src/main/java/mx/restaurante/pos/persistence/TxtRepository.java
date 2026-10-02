package mx.restaurante.pos.persistence;

import mx.restaurante.pos.model.Category;
import mx.restaurante.pos.model.Product;
import mx.restaurante.pos.model.Sale;
import mx.restaurante.pos.model.DiningTable;
import mx.restaurante.pos.model.OrderLine;
import mx.restaurante.pos.model.Ingredient;
import mx.restaurante.pos.model.RecipeLine;
import mx.restaurante.pos.model.Refund;
import mx.restaurante.pos.model.CashClosure;
import mx.restaurante.pos.model.TicketResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Persistencia deliberadamente sencilla: un registro separado por línea y campos separados por tabulador. */
public class TxtRepository {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final Path directory;
    public TxtRepository(Path directory) { this.directory = directory; }

    public Map<String, Category> readCategories() throws IOException {
        Map<String, Category> result = new LinkedHashMap<>();
        for (String[] f : rows("categorias.txt", 3)) result.put(f[0], new Category(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2])));
        return result;
    }
    public Map<String, Product> readProducts() throws IOException {
        Map<String, Product> result = new LinkedHashMap<>();
        for (String[] f : rows("productos.txt", 6)) result.put(f[0], new Product(f[0], f[1], f[2], Double.parseDouble(f[3]), Integer.parseInt(f[4]), f[5]));
        return result;
    }
    public List<Sale> readSales() throws IOException {
        return rawRows("ventas.txt").stream().map(f -> {
            if (f.length == 11) return new Sale(f[0], f[1], f[2], Integer.parseInt(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]), Double.parseDouble(f[7]), Double.parseDouble(f[8]), Double.parseDouble(f[9]), LocalDateTime.parse(f[10], DATE));
            if (f.length == 9) return new Sale(f[0], f[1], "", 0, Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]), LocalDateTime.parse(f[8], DATE));
            return null;
        }).filter(java.util.Objects::nonNull).toList();
    }
    public Set<String> readReturnIds() throws IOException {
        return rawRows("devoluciones.txt").stream().filter(f -> f.length == 11 || f.length == 9).map(f -> f[0]).collect(Collectors.toSet());
    }
    public Map<Integer, DiningTable> readTables() throws IOException {
        Map<Integer, DiningTable> result = new LinkedHashMap<>();
        for (String[] f : rows("mesas.txt", 3)) result.put(Integer.parseInt(f[0]), new DiningTable(Integer.parseInt(f[0]), f[1], f[2]));
        return result;
    }
    public void writeTables(Map<Integer, DiningTable> values) throws IOException {
        write("mesas.txt", values.values().stream().map(t -> line(t.number(), t.status(), t.orderId())).toList());
    }
    public List<OrderLine> readOrders() throws IOException {
        return rawRows("comandas.txt").stream().map(f -> {
            if (f.length == 9) return new OrderLine(f[0], f[1], Integer.parseInt(f[2]), f[3], f[4], Integer.parseInt(f[5]), f[6], f[7], LocalDateTime.parse(f[8], DATE));
            if (f.length == 8) return new OrderLine(f[0], "", Integer.parseInt(f[1]), f[2], f[3], Integer.parseInt(f[4]), f[5], f[6], LocalDateTime.parse(f[7], DATE));
            return null;
        }).filter(java.util.Objects::nonNull).toList();
    }
    public void writeOrders(List<OrderLine> values) throws IOException {
        write("comandas.txt", values.stream().map(o -> line(o.id(), o.orderId(), o.tableNumber(), o.sku(), o.productName(), o.quantity(), o.modifiers(), o.kitchenStatus(), o.createdAt().format(DATE))).toList());
    }
    public Map<String, Ingredient> readIngredients() throws IOException {
        Map<String, Ingredient> result = new LinkedHashMap<>();
        for (String[] f : rows("insumos.txt", 5)) result.put(f[0], new Ingredient(f[0], f[1], f[2], Double.parseDouble(f[3]), f[4]));
        return result;
    }
    public void writeIngredients(Map<String, Ingredient> values) throws IOException {
        write("insumos.txt", values.values().stream().map(i -> line(i.sku(), i.name(), i.unit(), i.stock(), i.supplier())).toList());
    }
    public List<RecipeLine> readRecipes() throws IOException {
        return rows("recetas.txt", 3).stream().map(f -> new RecipeLine(f[0], f[1], Double.parseDouble(f[2]))).toList();
    }
    public void writeRecipes(List<RecipeLine> values) throws IOException {
        write("recetas.txt", values.stream().map(r -> line(r.productSku(), r.ingredientSku(), r.quantityPerProduct())).toList());
    }
    public List<Refund> readRefunds() throws IOException {
        return rows("devoluciones-detalle.txt", 6).stream().map(f -> new Refund(f[0], f[1], f[2], Integer.parseInt(f[3]), Double.parseDouble(f[4]), LocalDateTime.parse(f[5], DATE))).toList();
    }
    public void appendRefund(Refund r) throws IOException { append("devoluciones-detalle.txt", line(r.id(), r.saleId(), r.sku(), r.quantity(), r.amount(), r.date().format(DATE))); }
    public void appendPayment(String ticketId, String method, double amount, LocalDateTime date) throws IOException {
        append("pagos.txt", line(ticketId, method, amount, date.format(DATE)));
    }
    public Path exportTicket(TicketResult ticket) throws IOException {
        Path folder = directory.resolve("tickets"); Files.createDirectories(folder);
        Path file = folder.resolve("ticket-" + ticket.id() + ".txt");
        List<String> lines = new java.util.ArrayList<>(List.of("MESA Y MIGA · TICKET", "Folio: " + ticket.id(), "Fecha: " + LocalDateTime.now(), ""));
        ticket.lines().forEach(s -> lines.add(s.quantity() + " × " + s.description() + " | $" + String.format("%.2f", s.total())));
        lines.add(""); lines.add(String.format("Subtotal: $%.2f", ticket.subtotal())); lines.add(String.format("IVA: $%.2f", ticket.iva()));
        lines.add(String.format("IEPS: $%.2f", ticket.ieps())); lines.add(String.format("Total: $%.2f", ticket.total())); lines.add("PAGOS");
        ticket.payments().forEach(p -> lines.add(p.method() + ": $" + String.format("%.2f", p.amount())));
        lines.add(String.format("Recibido: $%.2f", ticket.received())); lines.add(String.format("Cambio: $%.2f", ticket.change()));
        Files.write(file, lines, StandardCharsets.UTF_8); return file;
    }
    public List<String[]> readPayments() throws IOException { return rows("pagos.txt", 4); }
    public void appendTicketLine(String ticketId, String saleId) throws IOException { append("ticket-ventas.txt", line(ticketId, saleId)); }
    public List<String[]> readTicketLines() throws IOException { return rows("ticket-ventas.txt", 2); }
    public List<CashClosure> readClosures() throws IOException {
        return rows("cierres.txt", 7).stream().map(f -> new CashClosure(f[0], f[1], LocalDateTime.parse(f[2], DATE), LocalDateTime.parse(f[3], DATE),
                Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6]))).toList();
    }
    public void appendClosure(CashClosure c) throws IOException {
        append("cierres.txt", line(c.id(), c.type(), c.from().format(DATE), c.to().format(DATE), c.grossSales(), c.refunds(), c.netSales()));
    }
    public String managerPassword() throws IOException {
        Path path = directory.resolve("clave-gerente.txt");
        if (!Files.exists(path)) {
            Files.createDirectories(directory);
            Files.writeString(path, "admin123", StandardCharsets.UTF_8);
        }
        return Files.readString(path, StandardCharsets.UTF_8).trim();
    }
    public void writeManagerPassword(String password) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("clave-gerente.txt"), password, StandardCharsets.UTF_8);
    }
    public void writeCategories(Map<String, Category> values) throws IOException {
        write("categorias.txt", values.values().stream().map(c -> line(c.name(), c.ivaRate(), c.iepsRate())).toList());
    }
    public void writeProducts(Map<String, Product> values) throws IOException {
        write("productos.txt", values.values().stream().map(p -> line(p.sku(), p.name(), p.category(), p.price(), p.stock(), p.supplier())).toList());
    }
    public void appendSale(Sale s) throws IOException { append("ventas.txt", saleLine(s)); }
    public void appendReturn(Sale s) throws IOException { append("devoluciones.txt", saleLine(s)); }
    public Path exportReport(List<Sale> sales) throws IOException {
        Files.createDirectories(directory.resolve("reportes"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path report = directory.resolve("reportes").resolve("reporte-ventas-" + stamp + ".txt");
        double subtotal = sales.stream().mapToDouble(Sale::subtotal).sum();
        double iva = sales.stream().mapToDouble(Sale::iva).sum();
        double ieps = sales.stream().mapToDouble(Sale::ieps).sum();
        double total = sales.stream().mapToDouble(Sale::total).sum();
        List<String> lines = new java.util.ArrayList<>(List.of("REPORTE DE VENTAS · RESTAURANTE POS", "Generado: " + LocalDateTime.now(),
                "Transacciones: " + sales.size(), String.format("Subtotal: $%.2f", subtotal), String.format("IVA: $%.2f", iva),
                String.format("IEPS: $%.2f", ieps), String.format("Total: $%.2f", total), "", "DETALLE"));
        sales.forEach(s -> lines.add(s.date() + " | " + s.id() + " | " + s.description() + " | $" + String.format("%.2f", s.total())));
        Files.write(report, lines, StandardCharsets.UTF_8);
        return report;
    }
    public Path exportReport(List<Sale> sales, List<Refund> refunds, java.time.LocalDate from,
                             java.time.LocalDate to, Map<String, Integer> bestSellers) throws IOException {
        Files.createDirectories(directory.resolve("reportes"));
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path report = directory.resolve("reportes").resolve("reporte-" + from + "-a-" + to + "-" + stamp + ".txt");
        double subtotal = sales.stream().mapToDouble(Sale::subtotal).sum();
        double iva = sales.stream().mapToDouble(Sale::iva).sum();
        double ieps = sales.stream().mapToDouble(Sale::ieps).sum();
        double gross = sales.stream().mapToDouble(Sale::total).sum();
        double returned = refunds.stream().mapToDouble(Refund::amount).sum();
        List<String> lines = new java.util.ArrayList<>(List.of("REPORTE DE VENTAS · RESTAURANTE POS", "Período: " + from + " a " + to,
                "Transacciones (renglones): " + sales.size(), String.format("Subtotal: $%.2f", subtotal), String.format("IVA: $%.2f", iva),
                String.format("IEPS: $%.2f", ieps), String.format("Venta bruta: $%.2f", gross), String.format("Devoluciones: $%.2f", returned),
                String.format("Venta neta: $%.2f", gross - returned), "", "PRODUCTOS MÁS VENDIDOS"));
        bestSellers.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> lines.add(e.getKey() + " | unidades: " + e.getValue()));
        lines.add(""); lines.add("DETALLE DE VENTAS");
        sales.forEach(s -> lines.add(s.date() + " | " + s.id() + " | " + s.description() + " | $" + String.format("%.2f", s.total())));
        lines.add(""); lines.add("DETALLE DE DEVOLUCIONES");
        refunds.forEach(r -> lines.add(r.date() + " | venta " + r.saleId() + " | cantidad " + r.quantity() + " | $" + String.format("%.2f", r.amount())));
        Files.write(report, lines, StandardCharsets.UTF_8);
        return report;
    }
    private List<String[]> rows(String filename, int expected) throws IOException {
        return rawRows(filename).stream().filter(fields -> fields.length == expected).toList();
    }
    private List<String[]> rawRows(String filename) throws IOException {
        Path file = directory.resolve(filename);
        if (!Files.exists(file)) return List.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.split("\\t", -1)).toList();
    }
    private void write(String filename, List<String> records) throws IOException {
        Files.createDirectories(directory);
        List<String> lines = new java.util.ArrayList<>();
        lines.add("# Formato separado por tabuladores. No editar mientras la aplicación esté abierta.");
        lines.addAll(records);
        Files.write(directory.resolve(filename), lines, StandardCharsets.UTF_8);
    }
    private void append(String filename, String value) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(filename), value + System.lineSeparator(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    private String saleLine(Sale s) { return line(s.id(), s.description(), s.sku(), s.quantity(), s.subtotal(), s.iva(), s.ieps(), s.total(), s.paid(), s.change(), s.date().format(DATE)); }
    private String line(Object... values) {
        return java.util.Arrays.stream(values).map(v -> String.valueOf(v).replace("\t", " ").replace("\n", " ")).collect(java.util.stream.Collectors.joining("\t"));
    }
}
