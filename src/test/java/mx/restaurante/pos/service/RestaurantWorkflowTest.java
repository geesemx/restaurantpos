package mx.restaurante.pos.service;

import mx.restaurante.pos.model.Ingredient;
import mx.restaurante.pos.model.Payment;
import mx.restaurante.pos.persistence.TxtRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestaurantWorkflowTest {
    @TempDir Path directory;

    @Test void tableOrdersKitchenRecipeAndPaymentsPersistAcrossRestart() throws Exception {
        PosService service = new PosService(new TxtRepository(directory));
        service.addIngredient(new Ingredient("PAN-1", "Pan", "pieza", 10, "Panadería"));
        service.setRecipe("ALM-001", "PAN-1", 2);
        var order = service.sendOrder(1, "ALM-001", 2, "Sin cebolla; término medio");
        assertEquals(10, service.ingredients().getFirst().stock(), 0.001, "La comanda reserva el insumo sin consumirlo todavía");
        service.updateKitchenStatus(order.id(), "PREPARANDO");
        service.updateKitchenStatus(order.id(), "LISTO");
        PosService reopened = new PosService(new TxtRepository(directory));
        assertEquals("Ocupada", reopened.tables().getFirst().status());
        assertEquals(order.orderId(), reopened.tables().getFirst().orderId());
        assertEquals(order.modifiers(), reopened.orders().getFirst().modifiers());
        reopened.requestBill(1);
        var ticket = reopened.checkoutTable(1, List.of(new Payment("Efectivo", 200), new Payment("Tarjeta", 200)));

        reopened = new PosService(new TxtRepository(directory));
        assertEquals("Disponible", reopened.tables().getFirst().status());
        assertEquals(6, reopened.ingredients().stream().filter(i -> i.sku().equals("PAN-1")).findFirst().orElseThrow().stock(), 0.001);
        assertEquals("COBRADA", reopened.orders().stream().filter(o -> o.id().equals(order.id())).findFirst().orElseThrow().kitchenStatus());
        assertEquals(400, ticket.received());
        assertEquals(54.32, ticket.change(), 0.001);
        assertTrue(Files.exists(directory.resolve("ticket-ventas.txt")));
        assertTrue(Files.readString(directory.resolve("pagos.txt")).contains("Tarjeta"));
    }

    @Test void cancelRequiresManagerAuthorizationAndFreesTable() {
        PosService service = new PosService(new TxtRepository(directory));
        var order = service.sendOrder(2, "ALM-001", 1, "Sin pepinillos");
        assertThrows(IllegalArgumentException.class, () -> service.cancelOrder(order.id(), "wrong"));
        service.cancelOrder(order.id(), "admin123");

        PosService reopened = new PosService(new TxtRepository(directory));
        assertEquals("Disponible", reopened.tables().stream().filter(t -> t.number() == 2).findFirst().orElseThrow().status());
        assertEquals("CANCELADA", reopened.orders().stream().filter(o -> o.id().equals(order.id())).findFirst().orElseThrow().kitchenStatus());
    }

    @Test void partialRefundsCannotExceedSaleAndAreReportedWithClosures() throws Exception {
        PosService service = new PosService(new TxtRepository(directory));
        var sale = service.checkout("ALM-001", 4, 800);
        service.returnPartial(sale.id(), 1);
        service.returnPartial(sale.id(), 2);
        assertThrows(IllegalArgumentException.class, () -> service.returnPartial(sale.id(), 2));
        assertEquals(3, service.refunds().stream().mapToInt(r -> r.quantity()).sum());

        var x = service.closeRegister("X");
        var z = service.closeRegister("Z");
        assertEquals("X", x.type()); assertEquals("Z", z.type());
        PosService reopened = new PosService(new TxtRepository(directory));
        assertEquals(2, reopened.closures().size());
        assertEquals(3, reopened.refunds().stream().mapToInt(r -> r.quantity()).sum());
        assertTrue(reopened.closeRegister("X").from().equals(z.to()), "El siguiente período empieza en el último Z");
        Path report = service.exportReport(LocalDate.now(), LocalDate.now());
        assertTrue(Files.readString(report).contains("PRODUCTOS MÁS VENDIDOS"));
        assertTrue(Files.readString(report).contains("Devoluciones:"));
    }
}
