package mx.restaurante.pos;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import mx.restaurante.pos.service.PosService;

import java.io.IOException;

/** Punto de entrada JavaFX. La lógica de negocio vive en PosService. */
public class RestauranteApp extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(RestauranteApp.class.getResource("/mx/restaurante/pos/ui/main-view.fxml"));
        loader.setControllerFactory(type -> {
            try { return type.getDeclaredConstructor(PosService.class).newInstance(PosService.defaultService()); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("No se pudo crear la pantalla", e); }
        });
        Scene scene = new Scene(loader.load(), 1200, 760);
        scene.getStylesheets().add(RestauranteApp.class.getResource("/mx/restaurante/pos/ui/styles.css").toExternalForm());
        stage.setTitle("Mesa y Miga · Punto de venta");
        stage.setMinWidth(980);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) { launch(args); }
}
