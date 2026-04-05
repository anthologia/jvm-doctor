package io.jvmdoctor;

import io.jvmdoctor.ui.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class JvmDoctorApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/io/jvmdoctor/main.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        Scene scene = new Scene(root, 1100, 700);
        scene.getStylesheets().add(getClass().getResource("/io/jvmdoctor/style.css").toExternalForm());

        primaryStage.setTitle("jvm-doctor — Thread Dump Analyzer");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(550);
        primaryStage.setMaximized(true);
        boolean launchedWithFile = openLaunchFile(controller);
        installOpenFileHandler(controller);
        scheduleSessionRestore(primaryStage, controller, launchedWithFile);
        primaryStage.show();
        Platform.runLater(() -> {
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }

    private boolean openLaunchFile(MainController controller) {
        List<File> launchFiles = getParameters().getUnnamed().stream()
                .filter(arg -> arg != null && !arg.isBlank())
                .filter(arg -> !arg.startsWith("-"))
                .map(File::new)
                .filter(File::isFile)
                .map(File::getAbsoluteFile)
                .toList();
        if (launchFiles.isEmpty()) {
            return false;
        }
        Platform.runLater(() -> controller.openDumpFile(launchFiles.get(0)));
        return true;
    }

    private void scheduleSessionRestore(Stage primaryStage, MainController controller, boolean launchedWithFile) {
        if (launchedWithFile) {
            return;
        }
        primaryStage.setOnShown(event -> Platform.runLater(() -> {
            if (controller.hasLoadedDump()) {
                return;
            }
            primaryStage.toFront();
            controller.promptRestoreLastSessionIfAvailable();
            primaryStage.requestFocus();
        }));
    }

    private void installOpenFileHandler(MainController controller) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }

        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
                return;
            }
            desktop.setOpenFileHandler(event -> {
                List<File> files = event.getFiles();
                if (files.isEmpty()) {
                    return;
                }
                Platform.runLater(() -> controller.openDumpFile(files.get(0)));
            });
        } catch (SecurityException | UnsupportedOperationException ignored) {
        }
    }
}
