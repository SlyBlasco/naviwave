package com.slyblasco.frontend;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;

public class NaviWaveApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(NaviWaveApplication.class.getResource("naviwave-view.fxml"));
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double height = screenBounds.getHeight() - (screenBounds.getHeight() * (25/100.0));
        double width = screenBounds.getWidth() - (screenBounds.getWidth() * (25/100.0));
        Scene scene = new Scene(fxmlLoader.load(), width, height);
        stage.setTitle("NaviWave");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/com/slyblasco/frontend/imgs/NaviWave-logo.png")));
        stage.setMaximized(true);
        stage.setScene(scene);
        stage.show();
    }
}
