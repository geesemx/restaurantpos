package mx.restaurante.pos.service;

import mx.restaurante.pos.model.*;
import mx.restaurante.pos.persistence.TxtRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Reglas de negocio del restaurante. Esta clase no depende de JavaFX. */
public class PosService {
    private final TxtRepository repository;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Product> products = new LinkedHashMap<>();
    private final List<Sale> sales = new ArrayList<>();
    private final Map<Integer, DiningTable> tables = new LinkedHashMap<>();
    private final List<OrderLine> orders = new ArrayList<>();
    private final Map<String, Ingredient> ingredients = new LinkedHashMap<>();
    private final List<RecipeLine> recipes = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<CashClosure> closures = new ArrayList<>();
    private final Set<String> legacyReturns = new HashSet<>();
    private String managerPassword;

    public PosService(TxtRepository repository) {
        this.repository = repository;
        try {
            categories.putAll(repository.readCategories());
            products.putAll(repository.readProducts());
            sales.addAll(repository.readSales());
            legacyReturns.addAll(repository.readReturnIds());
            tables.putAll(repository.readTables());
            orders.addAll(repository.readOrders());
            ingredients.putAll(repository.readIngredients());
            recipes.addAll(repository.readRecipes());
            refunds.addAll(repository.readRefunds());
            closures.addAll(repository.readClosures());
            managerPassword = repository.managerPassword();
            if (categories.isEmpty()) seed();
            if (tables.isEmpty()) {
                for (int i = 1; i <= 8; i++) tables.put(i, new DiningTable(i, "Disponible", ""));
                saveTables();
            }
        } catch (IOException e) { throw new IllegalStateException("No se pudieron cargar los archivos TXT", e); }
    }

    public static PosService defaultService() { return new PosService(new TxtRepository(Path.of("data"))); }
    private void seed() {
        addCategory(new Category("Alimentos", .16, 0)); addCategory(new Category("Bebidas sin IEPS", .16, 0));
        addCategory(new Category("Bebidas alcohólicas", .16, .08)); addCategory(new Category("Exentos", 0, 0));
        addProduct(new Product("ALM-001", "Hamburguesa de la casa", "Alimentos", 149, 24, "Carnes del Valle"));
        addProduct(new Product("BEB-001", "Agua mineral", "Bebidas sin IEPS", 42, 36, "Bebidas Centro"));
        addProduct(new Product("BEB-002", "Cerveza artesanal", "Bebidas alcohólicas", 89, 18, "Cervecería Local"));
    }

    public List<Category> categories() { return List.copyOf(categories.values()); }
    public List<Product> products() { return List.copyOf(products.values()); }
    public List<Sale> sales() { return List.copyOf(sales); }
    public List<DiningTable> tables() { return List.copyOf(tables.values()); }
    public List<OrderLine> orders() { return List.copyOf(orders); }
    public List<Ingredient> ingredients() { return List.copyOf(ingredients.values()); }
    public List<RecipeLine> recipes() { return List.copyOf(recipes); }
    public List<Refund> refunds() { return List.copyOf(refunds); }
    public List<CashClosure> closures() { return List.copyOf(closures); }
    public List<OrderLine> ordersForTable(int number) { return orders.stream().filter(o -> o.tableNumber() == number && !o.kitchenStatus().equals("CANCELADA") && !o.kitchenStatus().equals("COBRADA")).toList(); }

    public void addCategory(Category category) {
        if (category.name() == null || category.name().isBlank()) throw new IllegalArgumentException("El nombre de categoría es obligatorio.");
        if (category.ivaRate() < 0 || category.iepsRate() < 0) throw new IllegalArgumentException("Las tasas no pueden ser negativas.");
        categories.put(category.name(), category); persist(() -> repository.writeCategories(categories));
    }
    public void addProduct(Product product) {
        if (!categories.containsKey(product.category())) throw new IllegalArgumentException("Selecciona una categoría existente.");
        if (product.sku().isBlank() || product.name().isBlank() || product.price() < 0 || product.stock() < 0) throw new IllegalArgumentException("Revisa los datos del producto.");
        products.put(product.sku(), product); persist(() -> repository.writeProducts(products));
    }
    public void adjustStock(String sku, int amount) {
        Product p = requireProduct(sku);
        int newStock = p.stock() + amount;
        if (newStock < reservedProductUnits(sku)) throw new IllegalArgumentException("El inventario no puede ser menor que las unidades reservadas en comandas abiertas.");
        products.put(sku, withStock(p, newStock)); persist(() -> repository.writeProducts(products));
    }

    public void addIngredient(Ingredient ingredient) {
        if (ingredient.sku().isBlank() || ingredient.name().isBlank() || ingredient.unit().isBlank() || ingredient.stock() < 0)
            throw new IllegalArgumentException("Revisa los datos del insumo.");
        if (ingredient.stock() < reservedIngredientUnits(ingredient.sku())) throw new IllegalArgumentException("La existencia no cubre las comandas abiertas.");
        ingredients.put(ingredient.sku(), ingredient); persist(() -> repository.writeIngredients(ingredients));
    }
    public void adjustIngredient(String sku, double amount) {
        Ingredient old = requireIngredient(sku);
        double newStock = old.stock() + amount;
        if (newStock < reservedIngredientUnits(sku)) throw new IllegalArgumentException("El insumo no puede ser menor que lo reservado en comandas abiertas.");
        ingredients.put(sku, new Ingredient(old.sku(), old.name(), old.unit(), newStock, old.supplier()));
        persist(() -> repository.writeIngredients(ingredients));
    }
    public void setRecipe(String productSku, String ingredientSku, double quantityPerProduct) {
        requireProduct(productSku); requireIngredient(ingredientSku);
        if (orders.stream().anyMatch(o -> o.sku().equals(productSku) && isReserved(o)))
            throw new IllegalArgumentException("No cambies la receta mientras haya comandas abiertas de este producto.");
        if (quantityPerProduct <= 0) throw new IllegalArgumentException("La cantidad de receta debe ser mayor que cero.");
        recipes.removeIf(r -> r.productSku().equals(productSku) && r.ingredientSku().equals(ingredientSku));
        recipes.add(new RecipeLine(productSku, ingredientSku, quantityPerProduct));
        persist(() -> repository.writeRecipes(recipes));
    }

    public OrderLine sendOrder(int tableNumber, String sku, int quantity, String modifiers) {
        DiningTable table = requireTable(tableNumber); Product product = requireProduct(sku);
        if (table.status().equals("Cuenta solicitada")) throw new IllegalArgumentException("La mesa ya solicitó la cuenta.");
        if (quantity < 1) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
        ensureStockAvailable(product, quantity);
        String orderId = table.orderId().isBlank() ? UUID.randomUUID().toString().substring(0, 8).toUpperCase() : table.orderId();
        OrderLine line = new OrderLine(UUID.randomUUID().toString().substring(0, 8).toUpperCase(), orderId, tableNumber, sku, product.name(), quantity,
                clean(modifiers), "PENDIENTE", LocalDateTime.now());
        orders.add(line); tables.put(tableNumber, new DiningTable(tableNumber, "Ocupada", orderId));
        saveOrdersAndTables(); return line;
    }
    public void updateKitchenStatus(String orderLineId, String status) {
        if (!Set.of("PENDIENTE", "PREPARANDO", "LISTO", "ENTREGADO").contains(status)) throw new IllegalArgumentException("Estado de cocina inválido.");
        OrderLine line = requireOrder(orderLineId);
        if (line.kitchenStatus().equals("CANCELADA") || line.kitchenStatus().equals("COBRADA")) throw new IllegalArgumentException("La comanda ya está cerrada.");
        replaceOrder(line, new OrderLine(line.id(), line.orderId(), line.tableNumber(), line.sku(), line.productName(), line.quantity(), line.modifiers(), status, line.createdAt()));
        persist(() -> repository.writeOrders(orders));
    }
    public void requestBill(int tableNumber) {
        DiningTable table = requireTable(tableNumber);
        if (ordersForTable(tableNumber).isEmpty()) throw new IllegalArgumentException("La mesa no tiene pedidos activos.");
        tables.put(tableNumber, new DiningTable(tableNumber, "Cuenta solicitada", table.orderId())); saveTables();
    }
    public void cancelOrder(String orderLineId, String authorization) {
        if (!managerPassword.equals(authorization)) throw new IllegalArgumentException("Clave de gerente incorrecta.");
        OrderLine line = requireOrder(orderLineId);
        if (line.kitchenStatus().equals("COBRADA")) throw new IllegalArgumentException("No se puede cancelar una comanda cobrada.");
        replaceOrder(line, new OrderLine(line.id(), line.orderId(), line.tableNumber(), line.sku(), line.productName(), line.quantity(), line.modifiers(), "CANCELADA", line.createdAt()));
        int tableNumber = line.tableNumber();
        List<OrderLine> active = ordersForTable(tableNumber);
        DiningTable previous = requireTable(tableNumber);
        tables.put(tableNumber, active.isEmpty() ? new DiningTable(tableNumber, "Disponible", "") : new DiningTable(tableNumber, previous.status(), previous.orderId()));
        saveOrdersAndTables();
    }
    public void changeManagerPassword(String current, String next) {
        if (!managerPassword.equals(current)) throw new IllegalArgumentException("Clave actual incorrecta.");
        if (next == null || next.length() < 6) throw new IllegalArgumentException("La clave nueva debe tener al menos seis caracteres.");
        managerPassword = next; persist(() -> repository.writeManagerPassword(next));
    }

    public Sale checkout(String sku, int quantity, double paid) { return checkoutWithPayments(sku, quantity, List.of(new Payment("Efectivo", paid))).lines().getFirst(); }
    public TicketResult checkout(String sku, int quantity, List<Double> payments) {
        return checkoutWithPayments(sku, quantity, payments.stream().map(p -> new Payment("Efectivo", p)).toList());
    }
    public TicketResult checkoutWithPayments(String sku, int quantity, List<Payment> payments) {
        Product p = requireProduct(sku); if (quantity < 1) throw new IllegalArgumentException("Cantidad inválida.");
        ensureStockAvailable(p, quantity);
        return checkoutItems(null, List.of(new OrderLine("", "", 0, sku, p.name(), quantity, "", "LISTO", LocalDateTime.now())), payments);
    }
    public TicketResult checkoutTable(int tableNumber, List<Payment> payments) {
        DiningTable table = requireTable(tableNumber);
        if (!table.status().equals("Cuenta solicitada")) throw new IllegalArgumentException("Solicita la cuenta antes de cobrar.");
        List<OrderLine> lines = ordersForTable(tableNumber);
        if (lines.isEmpty()) throw new IllegalArgumentException("La mesa no tiene productos pendientes.");
        if (lines.stream().anyMatch(line -> !line.kitchenStatus().equals("LISTO") && !line.kitchenStatus().equals("ENTREGADO")))
            throw new IllegalArgumentException("Todas las comandas deben estar listas antes de cobrar.");
        return checkoutItems(tableNumber, lines, payments);
    }
    private TicketResult checkoutItems(Integer tableNumber, List<OrderLine> lines, List<Payment> payments) {
        if (payments == null || payments.isEmpty() || payments.stream().anyMatch(p -> p.amount() <= 0 || !Set.of("Efectivo", "Tarjeta", "Transferencia", "Vale").contains(p.method())))
            throw new IllegalArgumentException("Agrega pagos válidos: efectivo, tarjeta, transferencia o vale.");
        List<Sale> ticketLines = new ArrayList<>(); double subtotal = 0, iva = 0, ieps = 0, total = 0;
        for (OrderLine order : lines) {
            Product product = requireProduct(order.sku()); Category category = categories.get(product.category());
            double itemSubtotal = product.price() * order.quantity(); double itemIva = itemSubtotal * category.ivaRate();
            double itemIeps = itemSubtotal * category.iepsRate(); double itemTotal = round(itemSubtotal + itemIva + itemIeps);
            subtotal += itemSubtotal; iva += itemIva; ieps += itemIeps; total += itemTotal;
            ticketLines.add(new Sale(UUID.randomUUID().toString().substring(0, 8).toUpperCase(), product.name(), product.sku(), order.quantity(),
                    round(itemSubtotal), round(itemIva), round(itemIeps), itemTotal, 0, 0, LocalDateTime.now()));
        }
        double received = payments.stream().mapToDouble(Payment::amount).sum(); total = round(total);
        if (received < total) throw new IllegalArgumentException("El total de pagos es menor al total de la cuenta.");
        String ticketId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Sale last = ticketLines.removeLast();
        ticketLines.add(new Sale(last.id(), last.description(), last.sku(), last.quantity(), last.subtotal(), last.iva(), last.ieps(), last.total(), round(received), round(received - total), last.date()));
        LocalDateTime now = LocalDateTime.now();
        for (OrderLine order : lines) consumeStock(order);
        if (tableNumber != null) for (OrderLine old : lines)
            replaceOrder(old, new OrderLine(old.id(), old.orderId(), old.tableNumber(), old.sku(), old.productName(), old.quantity(), old.modifiers(), "COBRADA", old.createdAt()));
        ticketLines.forEach(sale -> { sales.add(sale); persist(() -> repository.appendSale(sale)); persist(() -> repository.appendTicketLine(ticketId, sale.id())); });
        for (Payment payment : payments) persist(() -> repository.appendPayment(ticketId, payment.method(), payment.amount(), now));
        persist(() -> repository.writeProducts(products)); persist(() -> repository.writeIngredients(ingredients)); persist(() -> repository.writeOrders(orders));
        if (tableNumber != null) { DiningTable table = requireTable(tableNumber); tables.put(tableNumber, new DiningTable(tableNumber, "Disponible", "")); saveTables(); }
        return new TicketResult(ticketId, List.copyOf(ticketLines), round(subtotal), round(iva), round(ieps), total,
                round(received), round(received - total), List.copyOf(payments));
    }

    public Refund returnPartial(String saleId, int quantity) {
        Sale sale = sales.stream().filter(s -> s.id().equals(saleId)).findFirst().orElseThrow(() -> new IllegalArgumentException("No existe el renglón de venta."));
        if (quantity < 1 || sale.quantity() < quantity) throw new IllegalArgumentException("Cantidad de devolución inválida.");
        if (legacyReturns.contains(saleId)) throw new IllegalArgumentException("Esta venta ya fue devuelta completamente.");
        int alreadyReturned = refunds.stream().filter(r -> r.saleId().equals(saleId)).mapToInt(Refund::quantity).sum();
        if (alreadyReturned + quantity > sale.quantity()) throw new IllegalArgumentException("La cantidad excede lo vendido menos las devoluciones anteriores.");
        double unitPrice = sale.total() / sale.quantity();
        double amount = alreadyReturned + quantity == sale.quantity() ? round(sale.total() - refunds.stream().filter(r -> r.saleId().equals(saleId)).mapToDouble(Refund::amount).sum()) : round(unitPrice * quantity);
        Refund refund = new Refund(UUID.randomUUID().toString().substring(0, 8).toUpperCase(), saleId, sale.sku(), quantity, amount, LocalDateTime.now());
        refunds.add(refund); persist(() -> repository.appendRefund(refund));
        Product product = requireProduct(sale.sku());
        if (recipeFor(sale.sku()).isEmpty()) {
            products.put(product.sku(), withStock(product, product.stock() + quantity)); persist(() -> repository.writeProducts(products));
        }
        if (alreadyReturned + quantity == sale.quantity()) legacyReturns.add(saleId);
        return refund;
    }
    public void returnSale(String saleId) {
        Sale sale = sales.stream().filter(s -> s.id().equals(saleId)).findFirst().orElseThrow(() -> new IllegalArgumentException("No se encontró la venta."));
        int remaining = sale.quantity() - refunds.stream().filter(r -> r.saleId().equals(saleId)).mapToInt(Refund::quantity).sum();
        returnPartial(saleId, remaining);
    }

    public CashClosure closeRegister(String type) {
        if (!type.equals("X") && !type.equals("Z")) throw new IllegalArgumentException("El tipo de cierre debe ser X o Z.");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = closures.stream().filter(c -> c.type().equals("Z")).map(CashClosure::to).max(LocalDateTime::compareTo).orElse(LocalDateTime.MIN);
        double gross = sales.stream().filter(s -> !s.date().isBefore(from) && !s.date().isAfter(now)).mapToDouble(Sale::total).sum();
        double returned = refunds.stream().filter(r -> !r.date().isBefore(from) && !r.date().isAfter(now)).mapToDouble(Refund::amount).sum();
        CashClosure closure = new CashClosure(UUID.randomUUID().toString().substring(0, 8).toUpperCase(), type, from, now, round(gross), round(returned), round(gross - returned));
        closures.add(closure); persist(() -> repository.appendClosure(closure)); return closure;
    }
    public Path exportReport(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate)) throw new IllegalArgumentException("El período final debe ser posterior al inicial.");
        LocalDateTime from = fromDate.atStartOfDay(), to = toDate.plusDays(1).atStartOfDay();
        List<Sale> filtered = sales.stream().filter(s -> !s.date().isBefore(from) && s.date().isBefore(to)).toList();
        List<Refund> filteredRefunds = refunds.stream().filter(r -> !r.date().isBefore(from) && r.date().isBefore(to)).toList();
        Map<String, Integer> bestSellers = filtered.stream().collect(Collectors.groupingBy(Sale::description,
                Collectors.summingInt(Sale::quantity)));
        return persistResult(() -> repository.exportReport(filtered, filteredRefunds, fromDate, toDate, bestSellers));
    }
    public Path exportReport() { return exportReport(LocalDate.now().minusYears(10), LocalDate.now()); }
    public Path exportTicket(TicketResult ticket) {
        return persistResult(() -> repository.exportTicket(ticket));
    }

    private void ensureStockAvailable(Product product, int quantity) {
        List<RecipeLine> recipe = recipeFor(product.sku());
        if (recipe.isEmpty()) {
            int reserved = reservedProductUnits(product.sku());
            if (product.stock() - reserved < quantity) throw new IllegalArgumentException("No hay suficientes unidades disponibles (incluye comandas abiertas).");
        } else {
            for (RecipeLine ingredientLine : recipe) {
                Ingredient ingredient = requireIngredient(ingredientLine.ingredientSku());
                double reserved = reservedIngredientUnits(ingredientLine.ingredientSku());
                if (ingredient.stock() - reserved < quantity * ingredientLine.quantityPerProduct())
                    throw new IllegalArgumentException("Falta insumo: " + ingredient.name());
            }
        }
    }
    private void consumeStock(OrderLine order) {
        List<RecipeLine> recipe = recipeFor(order.sku());
        if (recipe.isEmpty()) {
            Product p = requireProduct(order.sku()); products.put(p.sku(), withStock(p, p.stock() - order.quantity()));
        } else for (RecipeLine item : recipe) {
            Ingredient in = requireIngredient(item.ingredientSku());
            ingredients.put(in.sku(), new Ingredient(in.sku(), in.name(), in.unit(), in.stock() - item.quantityPerProduct() * order.quantity(), in.supplier()));
        }
    }
    private List<RecipeLine> recipeFor(String sku) { return recipes.stream().filter(r -> r.productSku().equals(sku)).toList(); }
    private boolean isReserved(OrderLine o) { return !Set.of("CANCELADA", "COBRADA").contains(o.kitchenStatus()); }
    private int reservedProductUnits(String sku) {
        if (!recipeFor(sku).isEmpty()) return 0;
        return orders.stream().filter(o -> o.sku().equals(sku) && isReserved(o)).mapToInt(OrderLine::quantity).sum();
    }
    private double reservedIngredientUnits(String ingredientSku) {
        return orders.stream().filter(this::isReserved).mapToDouble(o -> recipes.stream()
                .filter(r -> r.productSku().equals(o.sku()) && r.ingredientSku().equals(ingredientSku))
                .mapToDouble(r -> o.quantity() * r.quantityPerProduct()).sum()).sum();
    }
    private void replaceOrder(OrderLine old, OrderLine replacement) { orders.set(orders.indexOf(old), replacement); }
    private Product requireProduct(String sku) { Product p = products.get(sku); if (p == null) throw new IllegalArgumentException("Selecciona un producto válido."); return p; }
    private Ingredient requireIngredient(String sku) { Ingredient i = ingredients.get(sku); if (i == null) throw new IllegalArgumentException("No existe el insumo " + sku + "."); return i; }
    private DiningTable requireTable(int number) { DiningTable t = tables.get(number); if (t == null) throw new IllegalArgumentException("No existe la mesa " + number + "."); return t; }
    private OrderLine requireOrder(String id) { return orders.stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("No existe esa comanda.")); }
    private Product withStock(Product p, int stock) { return new Product(p.sku(), p.name(), p.category(), p.price(), stock, p.supplier()); }
    private void saveTables() { persist(() -> repository.writeTables(tables)); }
    private void saveOrdersAndTables() { persist(() -> repository.writeOrders(orders)); saveTables(); }
    private static String clean(String text) { return text == null ? "" : text.replace("\t", " ").replace("\n", " ").trim(); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private void persist(IoAction action) { try { action.run(); } catch (IOException e) { throw new IllegalStateException("Error guardando archivos TXT", e); } }
    private <T> T persistResult(IoSupplier<T> action) { try { return action.run(); } catch (IOException e) { throw new IllegalStateException("No se pudo generar el reporte", e); } }
    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    @FunctionalInterface private interface IoSupplier<T> { T run() throws IOException; }
}
