package mx.restaurante.pos.service;

import mx.restaurante.pos.model.Category;
import mx.restaurante.pos.model.Product;
import mx.restaurante.pos.persistence.TxtRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PosServiceTest {
    @TempDir Path directory;

    @Test void checkoutCalculatesTaxesChangeAndReducesStock() {
        PosService service = new PosService(new TxtRepository(directory));
        service.addCategory(new Category("Bebida", .16, .08));
        service.addProduct(new Product("B-1", "Cerveza", "Bebida", 100, 4, "Local"));

        var sale = service.checkout("B-1", 2, java.util.List.of(100.0, 100.0, 100.0));

        assertEquals(200, sale.subtotal());
        assertEquals(32, sale.iva());
        assertEquals(16, sale.ieps());
        assertEquals(248, sale.total());
        assertEquals(52, sale.change());
        assertEquals(2, service.products().stream().filter(p -> p.sku().equals("B-1")).findFirst().orElseThrow().stock());
    }

    @Test void checkoutRejectsInsufficientInventory() {
        PosService service = new PosService(new TxtRepository(directory));
        assertThrows(IllegalArgumentException.class, () -> service.checkout("ALM-001", 100, 100000));
    }

    @Test void checkoutRejectsUnderpaymentWithoutChangingStock() {
        PosService service = new PosService(new TxtRepository(directory));
        int before = service.products().stream().filter(p -> p.sku().equals("ALM-001")).findFirst().orElseThrow().stock();

        assertThrows(IllegalArgumentException.class, () -> service.checkout("ALM-001", 1, 1));

        assertEquals(before, service.products().stream().filter(p -> p.sku().equals("ALM-001")).findFirst().orElseThrow().stock());
        assertTrue(service.sales().isEmpty());
    }

    @Test void saleIsAvailableAfterReloadingTextFiles() {
        PosService first = new PosService(new TxtRepository(directory));
        first.checkout("ALM-001", 1, 200);

        PosService reloaded = new PosService(new TxtRepository(directory));

        assertEquals(1, reloaded.sales().size());
        assertEquals(23, reloaded.products().stream().filter(p -> p.sku().equals("ALM-001")).findFirst().orElseThrow().stock());
    }

    @Test void returnRestoresInventoryAndCannotBeRepeated() {
        PosService service = new PosService(new TxtRepository(directory));
        var sale = service.checkout("ALM-001", 2, 400);
        service.returnSale(sale.id());

        assertEquals(24, service.products().stream().filter(p -> p.sku().equals("ALM-001")).findFirst().orElseThrow().stock());
        assertThrows(IllegalArgumentException.class, () -> service.returnSale(sale.id()));
    }
}
