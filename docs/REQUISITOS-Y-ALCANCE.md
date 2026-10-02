# Requisitos y alcance implementado

## Lectura del documento de requisitos

El SRS describe un POS de escritorio JavaFX bajo MVC para una operación de restaurante. Sus funciones principales son el mapa de mesas, comandas con notas/modificadores y envío a cocina, cobro con IVA/IEPS y pagos múltiples, descuento de insumos por receta, devoluciones y reportes de ventas exportables a TXT. Define cuatro actores (mesero, cajero, cocina y administración), prohíbe comandar insumos sin existencias y exige autorización gerencial para cancelar comandas ya enviadas. La factura CFDI, las terminales físicas y el comercio electrónico quedan fuera del alcance inicial.

## Decisiones de arquitectura

- `model`: registros Java para categorías, productos y ventas.
- `service`: reglas de negocio fuera de la interfaz.
- `persistence`: lectura y escritura de archivos de texto en `data/`.
- `ui`: vista FXML, controlador JavaFX y hoja CSS.
- Java 21, Maven, JavaFX 21 y JUnit 5. No se usa una base de datos.

## Funciones implementadas

- Mapa de ocho mesas persistente en `mesas.txt`, con estados Disponible, Ocupada y Cuenta solicitada.
- Comandas por mesa persistidas en `comandas.txt`, con notas/modificadores y folio de pedido. La disponibilidad del menú o de los insumos se valida antes de aceptar una nueva línea.
- Pantalla de cocina con estados Pendiente, Preparando, Listo y Entregado. El cobro de mesa requiere que las líneas estén listas o entregadas.
- Recetas enlazadas a insumos. Las cantidades necesarias quedan reservadas de manera lógica por las comandas abiertas y se descuentan de `insumos.txt` al cobrar. El inventario de producto sin receta se descuenta del catálogo de productos.
- Cancelación de comanda con clave gerencial. La clave inicial es `admin123`, se guarda en `data/clave-gerente.txt` y se puede cambiar desde Cortes X/Z.
- Pagos combinados con efectivo, tarjeta, transferencia o vale. En el campo se captura, por ejemplo, `Efectivo:100, Tarjeta:50`. Se registra el desglose en `pagos.txt`, se calcula el cambio y se genera el ticket en TXT.
- Devoluciones de una parte o de todas las unidades de un renglón vendido. Las cantidades acumuladas no pueden exceder la venta. Los productos sin receta reingresan a inventario; los insumos de platillos preparados no se reponen.
- Cortes X parciales y Z de cierre, persistidos en `cierres.txt`. El siguiente período empieza al último Z.
- Exportación de reportes TXT por período con ventas, impuestos, devoluciones y clasificación por unidades vendidas.

## Límites conocidos

El ticket se exporta como TXT; todavía no hay impresión física. El formato TXT y la clave gerencial local no proporcionan cifrado ni concurrencia para varias cajas. No hay devoluciones de importe por medio de pago, descuentos/promociones, arqueo por denominación, edición/eliminación de catálogo ni sincronización en red. La clave inicial debe cambiarse antes de operar. Las tasas fiscales de ejemplo son configurables, pero el sistema no valida la legislación vigente ni genera CFDI. Esas exclusiones y la integración con terminal bancaria/e-commerce siguen fuera del alcance.

## Ejecución

Con JDK 21 y Maven instalados, desde la raíz del proyecto: `mvn clean javafx:run`. Ejecuta las pruebas con `mvn test`. La compilación configura `--release 21`. Los TXT contienen registros separados por tabuladores; reportes fechados se guardan en `data/reportes/` y tickets en `data/tickets/`.
