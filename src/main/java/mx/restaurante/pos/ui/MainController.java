package mx.restaurante.pos.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import mx.restaurante.pos.model.*;
import mx.restaurante.pos.service.PosService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Presentación JavaFX. Cada pantalla se compone en métodos pequeños para que sea fácil de seguir. */
public class MainController {
    @FXML private VBox content;
    private final PosService service;
    private final List<String> suppliers = new ArrayList<>();

    public MainController(PosService service) {
        this.service = service;
        try {
            Path file = Path.of("data/proveedores.txt");
            if (Files.exists(file)) suppliers.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            service.products().stream().map(Product::supplier).filter(s -> !s.isBlank()).distinct().forEach(s -> { if (!suppliers.contains(s)) suppliers.add(s); });
        } catch (IOException e) { showError(e.getMessage()); }
    }

    @FXML private void initialize() { showTables(); }

    @FXML public void showTables() {
        header("Salón y mesas", "Estado persistente de servicio · selecciona una mesa para agregar pedidos o solicitar la cuenta.");
        FlowPane grid = new FlowPane(14, 14);
        grid.setPrefWrapLength(760);
        for (DiningTable table : service.tables()) {
            Button tile = new Button("MESA " + table.number() + "\n" + table.status());
            tile.getStyleClass().add(stateClass(table.status()));
            tile.setPrefSize(165, 105);
            tile.setOnAction(event -> tableOptions(table.number()));
            grid.getChildren().add(tile);
        }
        content.getChildren().addAll(card(grid), note("Verde: disponible     Rojo: ocupada     Amarillo: cuenta solicitada. Las comandas abiertas quedan vinculadas a la mesa."));
    }

    private void tableOptions(int number) {
        ChoiceDialog<String> action = new ChoiceDialog<>("Agregar pedido", List.of("Agregar pedido", "Ver comanda", "Solicitar cuenta"));
        action.setTitle("Mesa " + number); action.setHeaderText("Estado: " + service.tables().stream().filter(t -> t.number() == number).findFirst().orElseThrow().status());
        action.setContentText("Acción:");
        action.showAndWait().ifPresent(choice -> run(() -> {
            switch (choice) {
                case "Agregar pedido" -> createOrder(number);
                case "Ver comanda" -> showTableOrder(number);
                case "Solicitar cuenta" -> { service.requestBill(number); info("Cuenta solicitada para la mesa " + number + ". Ve a Ventas y cobro para cobrar."); }
                default -> { }
            }
            showTables();
        }));
    }

    private void createOrder(int tableNumber) {
        Product selected = chooseProduct();
        if (selected == null) return;
        TextInputDialog quantity = new TextInputDialog("1"); quantity.setTitle("Nueva comanda"); quantity.setHeaderText("Cantidad para " + selected.name());
        quantity.showAndWait().ifPresent(q -> {
            TextInputDialog modifier = new TextInputDialog(); modifier.setTitle("Modificadores / notas"); modifier.setHeaderText("Ej.: término medio, sin cebolla, aderezo aparte");
            modifier.showAndWait().ifPresent(note -> run(() -> {
                OrderLine order = service.sendOrder(tableNumber, selected.sku(), Integer.parseInt(q), note);
                info("Comanda " + order.id() + " enviada a cocina para mesa " + tableNumber + ".");
            }));
        });
    }

    private void showTableOrder(int tableNumber) {
        List<OrderLine> lines = service.ordersForTable(tableNumber);
        String detail = lines.isEmpty() ? "Sin pedidos activos." : lines.stream().map(o -> o.quantity() + " × " + o.productName() +
                " · " + o.kitchenStatus() + (o.modifiers().isBlank() ? "" : " · " + o.modifiers())).collect(java.util.stream.Collectors.joining("\n"));
        info("Comanda mesa " + tableNumber + "\n" + detail);
    }

    private Product chooseProduct() {
        if (service.products().isEmpty()) { info("Primero agrega productos al menú."); return null; }
        ComboBox<Product> combo = new ComboBox<>(FXCollections.observableArrayList(service.products()));
        combo.setConverter(productConverter()); combo.getSelectionModel().selectFirst();
        Dialog<Product> dialog = new Dialog<>(); dialog.setTitle("Seleccionar producto"); dialog.setHeaderText("Producto disponible");
        ButtonType select = new ButtonType("Seleccionar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(select, ButtonType.CANCEL); dialog.getDialogPane().setContent(combo);
        dialog.setResultConverter(button -> button == select ? combo.getValue() : null);
        return dialog.showAndWait().orElse(null);
    }

    @FXML public void showProducts() {
        header("Productos", "Menú del restaurante con categoría, precio, existencia y proveedor.");
        TableView<Product> table = new TableView<>(FXCollections.observableArrayList(service.products()));
        addColumn(table, "SKU", Product::sku); addColumn(table, "Producto", Product::name); addColumn(table, "Categoría", Product::category);
        addColumn(table, "Precio", p -> money(p.price())); addColumn(table, "Existencia", p -> String.valueOf(p.stock())); addColumn(table, "Proveedor", Product::supplier);
        Button add = primary("Agregar producto", () -> addProduct(table));
        content.getChildren().addAll(card(table), add);
    }

    @FXML public void showCategories() {
        header("Categorías e impuestos", "Las tasas se aplican al subtotal de cada categoría al cobrar.");
        TableView<Category> table = new TableView<>(FXCollections.observableArrayList(service.categories()));
        addColumn(table, "Categoría", Category::name); addColumn(table, "IVA", c -> percent(c.ivaRate())); addColumn(table, "IEPS", c -> percent(c.iepsRate()));
        content.getChildren().addAll(card(table), primary("Agregar categoría", () -> addCategory(table)));
    }

    @FXML public void showSuppliers() {
        header("Proveedores", "Directorio local. Se puede asociar el nombre del proveedor al crear un producto.");
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(suppliers));
        content.getChildren().addAll(card(list), primary("Agregar proveedor", this::addSupplier));
    }

    @FXML public void showInventory() {
        header("Inventario", "Ajusta las existencias recibidas. El cobro reduce automáticamente las unidades vendidas.");
        TableView<Product> table = new TableView<>(FXCollections.observableArrayList(service.products()));
        addColumn(table, "SKU", Product::sku); addColumn(table, "Producto", Product::name); addColumn(table, "Existencia", p -> String.valueOf(p.stock()));
        Button adjust = primary("Registrar entrada", () -> {
            Product selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { info("Selecciona un producto para ajustar el inventario."); return; }
            TextInputDialog dialog = new TextInputDialog("1"); dialog.setTitle("Entrada de inventario"); dialog.setHeaderText("Unidades recibidas para " + selected.name());
            dialog.showAndWait().ifPresent(value -> run(() -> { int amount = Integer.parseInt(value); if (amount < 1) throw new IllegalArgumentException("La entrada debe ser mayor que cero."); service.adjustStock(selected.sku(), amount); showInventory(); }));
        });
        content.getChildren().addAll(card(table), adjust);
    }

    @FXML public void showSales() {
        header("Ventas y cobro", "Cobra artículos directos o una mesa con cuenta solicitada. Puedes combinar medios de pago.");
        TableView<Sale> table = new TableView<>(FXCollections.observableArrayList(service.sales()));
        addColumn(table, "Folio", Sale::id); addColumn(table, "Detalle", Sale::description); addColumn(table, "Subtotal", s -> money(s.subtotal()));
        addColumn(table, "IVA", s -> money(s.iva())); addColumn(table, "IEPS", s -> money(s.ieps())); addColumn(table, "Total", s -> money(s.total()));
        ComboBox<Product> product = new ComboBox<>(FXCollections.observableArrayList(service.products()));
        product.setPromptText("Producto"); product.setPrefWidth(210); product.setConverter(productConverter());
        TextField quantity = field("Cantidad", "1"); TextField paid = field("Efectivo:100, Tarjeta:50", ""); paid.setPrefWidth(220);
        Button checkout = primary("Cobrar artículo", () -> run(() -> {
            if (product.getValue() == null) throw new IllegalArgumentException("Selecciona un producto.");
            TicketResult ticket = service.checkoutWithPayments(product.getValue().sku(), Integer.parseInt(quantity.getText()), parsePayments(paid.getText()));
            showReceipt(ticket); showSales();
        }));
        Button tableCheckout = primary("Cobrar mesa", this::checkoutTable);
        HBox form = new HBox(10, product, quantity, paid, checkout, tableCheckout);
        content.getChildren().addAll(card(table), card(form), note("Medios aceptados: Efectivo, Tarjeta, Transferencia y Vale. Captura un medio o combina importes separados por coma."));
    }

    @FXML public void showTaxes() {
        header("Impuestos", "Tasas configuradas en cada categoría. Revisa las obligaciones aplicables al establecimiento antes de operar.");
        TableView<Category> table = new TableView<>(FXCollections.observableArrayList(service.categories()));
        addColumn(table, "Categoría", Category::name); addColumn(table, "IVA", c -> percent(c.ivaRate())); addColumn(table, "IEPS", c -> percent(c.iepsRate()));
        content.getChildren().addAll(card(table), note("La configuración es manual y no valida normativa fiscal. Bebidas alcohólicas usan el IEPS indicado en la categoría."));
    }

    @FXML public void showReturns() {
        header("Devoluciones", "Selecciona el renglón vendido e indica cuántas unidades devolver.");
        TableView<Sale> table = new TableView<>(FXCollections.observableArrayList(service.sales()));
        addColumn(table, "Folio", Sale::id); addColumn(table, "Detalle", Sale::description); addColumn(table, "Unidades", s -> String.valueOf(s.quantity())); addColumn(table, "Total", s -> money(s.total())); addColumn(table, "Fecha", s -> s.date().toString());
        Button refund = primary("Registrar devolución", () -> {
            Sale selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { info("Selecciona una venta."); return; }
            TextInputDialog quantity = new TextInputDialog(String.valueOf(selected.quantity())); quantity.setTitle("Devolución parcial"); quantity.setHeaderText("Unidades por devolver (máximo " + selected.quantity() + ")");
            quantity.showAndWait().ifPresent(value -> run(() -> { Refund result = service.returnPartial(selected.id(), Integer.parseInt(value)); info("Devolución " + result.id() + " registrada por " + money(result.amount())); showReturns(); }));
        });
        content.getChildren().addAll(card(table), refund);
    }

    @FXML public void showReports() {
        header("Reportes TXT", "Selecciona un período para consultar ventas, impuestos, devoluciones y productos más vendidos.");
        DatePicker from = new DatePicker(LocalDate.now().withDayOfMonth(1)); DatePicker to = new DatePicker(LocalDate.now());
        Button export = primary("Exportar reporte TXT", () -> run(() -> info("Reporte guardado en:\n" + service.exportReport(from.getValue(), to.getValue()).toAbsolutePath())));
        content.getChildren().addAll(card(new HBox(12, new Label("Desde"), from, new Label("Hasta"), to, export)));
    }

    @FXML public void showKitchen() {
        header("Comandas y cocina", "Las comandas y sus modificadores se conservan al reiniciar.");
        TableView<OrderLine> table = new TableView<>(FXCollections.observableArrayList(service.orders().stream().filter(o -> !o.kitchenStatus().equals("COBRADA") && !o.kitchenStatus().equals("CANCELADA")).toList()));
        addColumn(table, "Mesa", o -> String.valueOf(o.tableNumber())); addColumn(table, "Producto", OrderLine::productName);
        addColumn(table, "Cantidad", o -> String.valueOf(o.quantity())); addColumn(table, "Modificadores", OrderLine::modifiers); addColumn(table, "Estado", OrderLine::kitchenStatus);
        Button state = primary("Actualizar estado", () -> {
            OrderLine selected = table.getSelectionModel().getSelectedItem(); if (selected == null) { info("Selecciona una comanda."); return; }
            ChoiceDialog<String> choice = new ChoiceDialog<>("PREPARANDO", List.of("PENDIENTE", "PREPARANDO", "LISTO", "ENTREGADO")); choice.setHeaderText("Estado de cocina");
            choice.showAndWait().ifPresent(value -> run(() -> { service.updateKitchenStatus(selected.id(), value); showKitchen(); }));
        });
        Button cancel = primary("Cancelar con clave gerente", () -> {
            OrderLine selected = table.getSelectionModel().getSelectedItem(); if (selected == null) { info("Selecciona una comanda."); return; }
            PasswordField input = new PasswordField(); input.setPromptText("Clave de gerente");
            Dialog<ButtonType> password = new Dialog<>(); password.setTitle("Autorización gerencial"); password.setHeaderText("Confirma la anulación de la comanda");
            password.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); password.getDialogPane().setContent(input);
            password.showAndWait().filter(value -> value == ButtonType.OK).ifPresent(value -> run(() -> { service.cancelOrder(selected.id(), input.getText()); showKitchen(); }));
        });
        content.getChildren().addAll(card(table), new HBox(10, state, cancel));
    }

    @FXML public void showRecipes() {
        header("Recetas e insumos", "El inventario reserva insumos de las comandas abiertas y los descuenta al cobrar.");
        TableView<Ingredient> stock = new TableView<>(FXCollections.observableArrayList(service.ingredients()));
        addColumn(stock, "SKU", Ingredient::sku); addColumn(stock, "Insumo", Ingredient::name); addColumn(stock, "Unidad", Ingredient::unit);
        addColumn(stock, "Existencia", i -> String.format("%.3f", i.stock())); addColumn(stock, "Proveedor", Ingredient::supplier);
        TableView<RecipeLine> recipeTable = new TableView<>(FXCollections.observableArrayList(service.recipes()));
        addColumn(recipeTable, "Producto menú", r -> service.products().stream().filter(p -> p.sku().equals(r.productSku())).map(Product::name).findFirst().orElse(r.productSku()));
        addColumn(recipeTable, "Insumo", r -> service.ingredients().stream().filter(i -> i.sku().equals(r.ingredientSku())).map(Ingredient::name).findFirst().orElse(r.ingredientSku()));
        addColumn(recipeTable, "Cantidad / porción", r -> String.valueOf(r.quantityPerProduct()));
        Button addIngredient = primary("Agregar insumo", this::addIngredient);
        Button addRecipe = primary("Asignar componente a receta", this::addRecipeLine);
        content.getChildren().addAll(new Label("Inventario de insumos"), card(stock), new HBox(10, addIngredient, primary("Entrada de insumo", () -> adjustIngredient(stock))),
                new Label("Recetario"), card(recipeTable), addRecipe);
    }

    @FXML public void showClosures() {
        header("Cortes de caja", "El corte X consulta desde el último Z. El corte Z registra el cierre y fija el inicio del siguiente turno.");
        TableView<CashClosure> table = new TableView<>(FXCollections.observableArrayList(service.closures()));
        addColumn(table, "Tipo", CashClosure::type); addColumn(table, "Desde", c -> c.from().equals(LocalDateTime.MIN) ? "Inicio" : c.from().toString()); addColumn(table, "Hasta", c -> c.to().toString());
        addColumn(table, "Bruto", c -> money(c.grossSales())); addColumn(table, "Devoluciones", c -> money(c.refunds())); addColumn(table, "Neto", c -> money(c.netSales()));
        Button x = primary("Generar corte X", () -> createClosure("X")); Button z = primary("Cerrar turno Z", () -> createClosure("Z"));
        Button password = primary("Cambiar clave gerente", this::changeManagerPassword);
        content.getChildren().addAll(card(table), new HBox(10, x, z, password));
    }

    private javafx.util.StringConverter<Product> productConverter() {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(Product p) { return p == null ? "" : p.name() + " · " + money(p.price()); }
            @Override public Product fromString(String text) { return null; }
        };
    }
    private List<Payment> parsePayments(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Captura al menos un pago.");
        List<Payment> payments = new ArrayList<>();
        for (String component : text.split(",")) {
            String[] parts = component.trim().split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Usa el formato Medio:importe, por ejemplo Efectivo:100, Tarjeta:50.");
            payments.add(new Payment(parts[0].trim(), Double.parseDouble(parts[1].trim())));
        }
        return payments;
    }
    private void checkoutTable() {
        List<Integer> accountTables = service.tables().stream().filter(t -> t.status().equals("Cuenta solicitada")).map(DiningTable::number).toList();
        if (accountTables.isEmpty()) { info("No hay mesas con cuenta solicitada."); return; }
        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(accountTables.getFirst(), accountTables); dialog.setTitle("Cobrar mesa"); dialog.setHeaderText("Selecciona la mesa");
        dialog.showAndWait().ifPresent(number -> {
            TextInputDialog payment = new TextInputDialog("Efectivo:"); payment.setTitle("Medios de pago");
            payment.setHeaderText("Mesa " + number + " · usa Medio:importe, separados por coma");
            payment.showAndWait().ifPresent(value -> run(() -> { TicketResult ticket = service.checkoutTable(number, parsePayments(value)); showReceipt(ticket); showSales(); }));
        });
    }
    private void showReceipt(TicketResult ticket) {
        String rows = ticket.lines().stream().map(s -> s.quantity() + " × " + s.description() + "  " + money(s.total())).collect(java.util.stream.Collectors.joining("\n"));
        String payments = ticket.payments().stream().map(p -> p.method() + " " + money(p.amount())).collect(java.util.stream.Collectors.joining(" + "));
        String text = "TICKET " + ticket.id() + "\n" + rows + "\n\nSubtotal " + money(ticket.subtotal()) + "\nIVA " + money(ticket.iva()) +
                " · IEPS " + money(ticket.ieps()) + "\nTotal " + money(ticket.total()) + "\nPagos " + payments + "\nRecibido " + money(ticket.received()) + "\nCambio " + money(ticket.change());
        try { info(text + "\n\nCopia TXT: " + service.exportTicket(ticket).toAbsolutePath()); }
        catch (RuntimeException e) { info(text); showError("La venta se guardó, pero no se pudo exportar la copia TXT del ticket: " + e.getMessage()); }
    }
    private void addIngredient() {
        TextField sku = field("SKU", "INS-" + (service.ingredients().size() + 1)); TextField name = field("Nombre", "");
        TextField unit = field("Unidad (g, ml, pieza)", "g"); TextField stock = field("Existencia", "0"); TextField supplier = field("Proveedor", "");
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Nuevo insumo"); dialog.setHeaderText("Inventario de cocina");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.getDialogPane().setContent(new VBox(8, sku, name, unit, stock, supplier));
        dialog.showAndWait().filter(result -> result == ButtonType.OK).ifPresent(result -> run(() -> {
            service.addIngredient(new Ingredient(sku.getText().trim(), name.getText().trim(), unit.getText().trim(), Double.parseDouble(stock.getText()), supplier.getText().trim())); showRecipes();
        }));
    }
    private void adjustIngredient(TableView<Ingredient> table) {
        Ingredient selected = table.getSelectionModel().getSelectedItem(); if (selected == null) { info("Selecciona un insumo."); return; }
        TextInputDialog amount = new TextInputDialog("1"); amount.setTitle("Entrada de insumo"); amount.setHeaderText("Cantidad recibida en " + selected.unit());
        amount.showAndWait().ifPresent(value -> run(() -> { double qty = Double.parseDouble(value); if (qty <= 0) throw new IllegalArgumentException("La entrada debe ser positiva."); service.adjustIngredient(selected.sku(), qty); showRecipes(); }));
    }
    private void addRecipeLine() {
        if (service.products().isEmpty() || service.ingredients().isEmpty()) { info("Agrega productos e insumos antes de definir recetas."); return; }
        ComboBox<Product> product = new ComboBox<>(FXCollections.observableArrayList(service.products())); product.setConverter(productConverter()); product.getSelectionModel().selectFirst();
        ComboBox<Ingredient> ingredient = new ComboBox<>(FXCollections.observableArrayList(service.ingredients()));
        ingredient.setConverter(new javafx.util.StringConverter<>() { @Override public String toString(Ingredient i) { return i == null ? "" : i.name() + " · " + i.unit(); } @Override public Ingredient fromString(String value) { return null; } });
        ingredient.getSelectionModel().selectFirst(); TextField amount = field("Cantidad por producto", "1");
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Componente de receta"); dialog.setHeaderText("Cantidad de insumo que consume una porción");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.getDialogPane().setContent(new VBox(9, product, ingredient, amount));
        dialog.showAndWait().filter(result -> result == ButtonType.OK).ifPresent(result -> run(() -> {
            service.setRecipe(product.getValue().sku(), ingredient.getValue().sku(), Double.parseDouble(amount.getText())); showRecipes();
        }));
    }
    private void createClosure(String type) {
        String question = type.equals("Z") ? "El cierre Z termina el turno e inicia un nuevo período. ¿Continuar?" : "Generar corte parcial X?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL); confirm.setHeaderText("Corte " + type);
        confirm.showAndWait().filter(button -> button == ButtonType.OK).ifPresent(button -> run(() -> {
            CashClosure closure = service.closeRegister(type);
            info("Corte " + type + " · " + closure.id() + "\nVentas: " + money(closure.grossSales()) + "\nDevoluciones: " + money(closure.refunds()) + "\nNeto: " + money(closure.netSales()));
            showClosures();
        }));
    }
    private void changeManagerPassword() {
        PasswordField current = new PasswordField(); current.setPromptText("Clave actual");
        PasswordField next = new PasswordField(); next.setPromptText("Nueva clave (mínimo 6 caracteres)");
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Clave gerencial"); dialog.setHeaderText("Actualiza la autorización de cancelaciones");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(9, current, next));
        dialog.showAndWait().filter(value -> value == ButtonType.OK).ifPresent(value -> run(() -> { service.changeManagerPassword(current.getText(), next.getText()); info("Clave gerencial actualizada."); }));
    }

    private void addProduct(TableView<Product> table) {
        if (service.categories().isEmpty()) { info("Primero crea una categoría."); return; }
        Dialog<Product> dialog = new Dialog<>(); dialog.setTitle("Nuevo producto"); dialog.setHeaderText("Datos del menú");
        ButtonType save = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField sku = field("SKU", "ALM-" + (service.products().size() + 1)); TextField name = field("Nombre", "");
        ComboBox<String> category = new ComboBox<>(FXCollections.observableArrayList(service.categories().stream().map(Category::name).toList())); category.getSelectionModel().selectFirst();
        TextField price = field("Precio", "0"); TextField stock = field("Existencia", "0"); TextField supplier = field("Proveedor", "");
        VBox form = new VBox(9, sku, name, category, price, stock, supplier); dialog.getDialogPane().setContent(form);
        dialog.setResultConverter(button -> button == save ? new Product(sku.getText().trim(), name.getText().trim(), category.getValue(), Double.parseDouble(price.getText()), Integer.parseInt(stock.getText()), supplier.getText().trim()) : null);
        dialog.showAndWait().ifPresent(p -> run(() -> { if (p.name().isBlank() || p.sku().isBlank()) throw new IllegalArgumentException("SKU y nombre son obligatorios."); service.addProduct(p); showProducts(); }));
    }
    private void addCategory(TableView<Category> table) {
        TextInputDialog name = new TextInputDialog(); name.setTitle("Nueva categoría"); name.setHeaderText("Nombre de categoría");
        name.showAndWait().ifPresent(value -> {
            TextInputDialog iva = new TextInputDialog("0.16"); iva.setTitle("IVA"); iva.setHeaderText("Tasa IVA como decimal (16% = 0.16)");
            iva.showAndWait().ifPresent(vat -> {
                TextInputDialog ieps = new TextInputDialog("0"); ieps.setTitle("IEPS"); ieps.setHeaderText("Tasa IEPS como decimal (8% = 0.08)");
                ieps.showAndWait().ifPresent(excise -> run(() -> { service.addCategory(new Category(value.trim(), Double.parseDouble(vat), Double.parseDouble(excise))); showCategories(); }));
            });
        });
    }
    private void addSupplier() {
        TextInputDialog dialog = new TextInputDialog(); dialog.setTitle("Nuevo proveedor"); dialog.setHeaderText("Nombre del proveedor");
        dialog.showAndWait().ifPresent(value -> run(() -> {
            String name = value.trim(); if (name.isBlank()) throw new IllegalArgumentException("El nombre es obligatorio.");
            if (!suppliers.contains(name)) suppliers.add(name);
            Files.createDirectories(Path.of("data"));
            Files.write(Path.of("data/proveedores.txt"), suppliers, StandardCharsets.UTF_8);
            showSuppliers();
        }));
    }

    private void header(String title, String subtitle) {
        content.getChildren().clear();
        Label heading = new Label(title); heading.getStyleClass().add("page-title");
        Label sub = new Label(subtitle); sub.getStyleClass().add("page-subtitle");
        content.getChildren().addAll(heading, sub);
    }
    private VBox card(Node node) { VBox box = new VBox(node); box.getStyleClass().add("card"); VBox.setVgrow(node, javafx.scene.layout.Priority.ALWAYS); return box; }
    private Label note(String text) { Label label = new Label(text); label.getStyleClass().add("info-label"); label.setWrapText(true); return label; }
    private Button primary(String text, Runnable action) { Button button = new Button(text); button.getStyleClass().add("primary-button"); button.setOnAction(e -> action.run()); return button; }
    private TextField field(String prompt, String value) { TextField field = new TextField(value); field.setPromptText(prompt); return field; }
    private String stateClass(String state) { return switch (state) { case "Ocupada" -> "mesa-ocupada"; case "Cuenta solicitada" -> "mesa-cuenta"; default -> "mesa-disponible"; }; }
    private String money(double amount) { return String.format(java.util.Locale.forLanguageTag("es-MX"), "$%,.2f", amount); }
    private String percent(double rate) { return String.format(java.util.Locale.forLanguageTag("es-MX"), "%.1f%%", rate * 100); }
    private <T> void addColumn(TableView<T> table, String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title); column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue()))); column.setPrefWidth(125); table.getColumns().add(column);
    }
    private void run(ThrowingAction action) { try { action.run(); } catch (NumberFormatException e) { showError("Revisa los números capturados."); } catch (Exception e) { showError(e.getMessage()); } }
    private void showError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Ocurrió un error." : message); alert.setHeaderText("No se pudo completar la acción"); alert.showAndWait(); }
    private void info(String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, message); alert.setHeaderText(null); alert.showAndWait(); }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
