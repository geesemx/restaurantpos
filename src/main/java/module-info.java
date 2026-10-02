module mx.restaurante.pos {
    requires javafx.controls;
    requires javafx.fxml;

    exports mx.restaurante.pos;
    opens mx.restaurante.pos.ui to javafx.fxml;
}
