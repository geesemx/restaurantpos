# Restaurante POS

Aplicación de escritorio para restaurante, escrita en Java 21 con JavaFX, FXML, CSS y Maven. La vista sigue MVC: los controladores coordinan la interfaz, `PosService` aplica reglas de negocio y `TxtRepository` conserva los datos como archivos TXT, sin base de datos.

## Requisitos y ejecución

- JDK 21 (incluye compilador `javac`) y Maven.
- En la raíz del proyecto: `mvn clean javafx:run`.
- Pruebas: `mvn test`.

La primera ejecución crea la carpeta `data/`, ocho mesas y datos de menú de muestra. Desde una mesa se agregan platos y modificadores; la cocina actualiza su estado y caja cobra al solicitarse la cuenta. El cobro admite una o varias formas, por ejemplo `Efectivo:100, Tarjeta:50`. El archivo `data/clave-gerente.txt` se crea con clave inicial `admin123`; cámbiala desde Cortes X/Z.

En Recetas e insumos puedes dar de alta existencias y asociar componentes a cada producto. Los pedidos reservan existencias disponibles; cobrar consume los insumos de la receta. Los cortes X/Z, las devoluciones parciales y los reportes fechados también quedan guardados en TXT. El ticket se produce como TXT; no hay impresora ni terminal bancaria integradas.

Las tasas del menú de muestra son configurables: alimentos y bebidas sin alcohol usan IVA del 16%; bebidas alcohólicas tienen además IEPS del 8% de ejemplo. Confirma las tasas que correspondan a tu operación antes de usar el programa con ventas reales.

## Organización

```text
src/main/java/mx/restaurante/pos/
  model/        Categoría, producto y venta
  persistence/  Repositorio de texto tabulado
  service/      Reglas de inventario, impuestos y cobro
  ui/           Controlador JavaFX
src/main/resources/mx/restaurante/pos/ui/
  main-view.fxml
  styles.css
data/           Datos locales generados al ejecutar
```

El estado de requisitos y las limitaciones de esta primera entrega se detallan en [docs/REQUISITOS-Y-ALCANCE.md](docs/REQUISITOS-Y-ALCANCE.md).
