module com.slyblasco.frontend {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires javafx.media;
    requires jdk.jsobject;   // netscape.javascript.JSObject: puente Java <-> JavaScript del WebView
    requires java.desktop;   // javax.sound.sampled: reproducir el audio en vivo

    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires java.net.http;
    requires java.sql;
    requires com.fasterxml.jackson.databind;

    opens com.slyblasco.frontend to javafx.fxml;
    exports com.slyblasco.frontend;
    exports com.slyblasco.frontend.api;
    opens com.slyblasco.frontend.api to javafx.fxml;
    opens com.slyblasco.frontend.models to com.fasterxml.jackson.databind;
}