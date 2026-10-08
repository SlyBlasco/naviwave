package com.slyblasco.frontend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slyblasco.frontend.api.ApiCalls;
import com.slyblasco.frontend.models.AudioModel;
import com.slyblasco.frontend.models.BarcoModel;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NaviWaveController {

    // true = solo barcos vistos en las ultimas 24 h; false = todos (util para probar con datos viejos)
    private static final boolean SOLO_ULTIMAS_24H = true;
    private static final int REFRESCO_SEGUNDOS = 10;
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final String ESTILO_BLOQUE =
            "-fx-background-color: white; -fx-border-color: #b0b0b0; -fx-border-radius: 4; "
                    + "-fx-background-radius: 4; -fx-padding: 8; -fx-cursor: hand;";
    private static final String ESTILO_BLOQUE_SELECCIONADO =
            "-fx-background-color: #ffe9e9; -fx-border-color: #e53935; -fx-border-width: 2; "
                    + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 7; -fx-cursor: hand;";

    private final ApiCalls api = new ApiCalls();
    private final ObjectMapper mapper = new ObjectMapper();

    @FXML private ScrollPane scrollLista;
    @FXML private VBox listaBarcos;
    @FXML private WebView webMapa;

    private final Map<Integer, VBox> bloques = new HashMap<>();
    private Integer mmsiSeleccionado = null;
    private String ultimoJson = "[]";
    private boolean mapaListo = false;
    private boolean cargando = false;

    // Se guarda en un campo a proposito: si solo vive dentro de un metodo, el recolector de basura
    // puede eliminarlo y el JavaScript dejaria de poder llamarlo.
    private final PuenteMapa puente = new PuenteMapa();

    // ---- Pestana de grabaciones ----
    @FXML private Tab tab_grabaciones;
    @FXML private TableView<AudioModel> tablaAudios;
    @FXML private TableColumn<AudioModel, String> colFecha;
    @FXML private TableColumn<AudioModel, String> colNombre;
    @FXML private Label lblAudioActual;
    @FXML private Label lblTiempo;
    @FXML private Button btnPlayPausa;
    @FXML private Slider sliderProgreso;

    private MediaPlayer reproductor;
    private Integer idCargado = null;
    private boolean arrastrandoSlider = false;
    private int contadorSolicitudes = 0;
    private Path carpetaTemporal;
    private final Map<Integer, Path> audiosDescargados = new HashMap<>();

    @FXML
    public void initialize() {
        scrollLista.setFitToWidth(true);
        configurarMapa();
        cargarBarcos();
        configurarGrabaciones();

        Timeline refresco = new Timeline(new KeyFrame(Duration.seconds(REFRESCO_SEGUNDOS), e -> cargarBarcos()));
        refresco.setCycleCount(Animation.INDEFINITE);
        refresco.play();
    }

    // ---------- Mapa (WebView) ----------

    private void configurarMapa() {
        WebEngine engine = webMapa.getEngine();
        // OpenStreetMap pide que quien descarga sus tiles se identifique.
        engine.setUserAgent("NaviWave/1.0 (proyecto personal)");

        engine.getLoadWorker().stateProperty().addListener((obs, anterior, estado) -> {
            if (estado == Worker.State.SUCCEEDED) {
                // JavaScript -> Java: se le entrega al mapa el objeto "javaBridge"
                ventanaMapa().setMember("javaBridge", puente);
                mapaListo = true;
                enviarBarcosAlMapa();
                Platform.runLater(this::ajustarTamanoMapa);
            }
        });
        // Si la ventana o la pestana cambia de tamano, el mapa recalcula el suyo.
        webMapa.widthProperty().addListener((obs, anterior, nuevo) -> ajustarTamanoMapa());
        webMapa.heightProperty().addListener((obs, anterior, nuevo) -> ajustarTamanoMapa());
        engine.load(getClass().getResource("map.html").toExternalForm());
    }

    private JSObject ventanaMapa() {
        return (JSObject) webMapa.getEngine().executeScript("window");
    }

    private void ajustarTamanoMapa() {
        if (mapaListo) ventanaMapa().call("ajustarTamano");
    }

    private void enviarBarcosAlMapa() {
        if (!mapaListo) return;
        // Java -> JavaScript: se pasa el JSON como argumento (texto), no pegado dentro de codigo,
        // para que ningun nombre de barco pueda colarse como codigo JavaScript.
        ventanaMapa().call("setBarcos", ultimoJson);
    }

    /** Puente que el JavaScript del mapa puede llamar. Debe ser publico. */
    public class PuenteMapa {
        public void log(String mensaje) {
            System.out.println("[mapa] " + mensaje);
        }

        public void barcoSeleccionado(int mmsi) {
            Platform.runLater(() -> seleccionarBarco(mmsi, false));
        }
    }

    // ---------- Carga de datos (sin congelar la ventana) ----------

    private void cargarBarcos() {
        if (cargando) return;
        cargando = true;

        Task<String> tarea = new Task<>() {
            @Override
            protected String call() {
                // Corre en un hilo aparte: la llamada HTTP puede tardar sin bloquear la interfaz.
                return SOLO_ULTIMAS_24H ? api.getJSONBarcos24Horas() : api.getJSONTodosBarcos();
            }
        };
        // Estos dos corren de vuelta en el hilo de la interfaz, donde si se pueden tocar los controles.
        tarea.setOnSucceeded(e -> {
            cargando = false;
            mostrarBarcos(tarea.getValue());
        });
        tarea.setOnFailed(e -> {
            cargando = false;
            System.err.println("No se pudo cargar los barcos: " + tarea.getException());
        });

        Thread hilo = new Thread(tarea, "carga-barcos");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void mostrarBarcos(String json) {
        if (json == null || json.isBlank()) {
            System.err.println("Sin respuesta del backend; se conserva lo que ya se mostraba.");
            return;
        }
        List<BarcoModel> barcos;
        try {
            barcos = mapper.readValue(json, new TypeReference<List<BarcoModel>>() {});
        } catch (JsonProcessingException ex) {
            System.err.println("JSON invalido: " + ex.getMessage());
            return;
        }
        ultimoJson = json;
        reconstruirLista(barcos);
        enviarBarcosAlMapa();
    }

    // ---------- Lista de bloques ----------

    private void reconstruirLista(List<BarcoModel> barcos) {
        listaBarcos.getChildren().clear();
        bloques.clear();
        for (BarcoModel barco : barcos) {
            VBox bloque = crearBloque(barco);
            bloques.put(barco.getMmsi(), bloque);
            listaBarcos.getChildren().add(bloque);
        }
        if (mmsiSeleccionado != null && bloques.containsKey(mmsiSeleccionado)) {
            marcarBloque(mmsiSeleccionado);
        } else {
            mmsiSeleccionado = null;
        }
    }

    private VBox crearBloque(BarcoModel barco) {
        String nombre = (barco.getNombre() == null || barco.getNombre().isBlank()) ? "(sin nombre)" : barco.getNombre();
        Timestamp visto = barco.getFechaActualizada();
        String fecha = (visto == null) ? "?" : visto.toLocalDateTime().format(FORMATO_FECHA);

        Label lblNombre = etiqueta("- " + nombre);
        lblNombre.setStyle("-fx-font-weight: bold;");
        Label lblMmsi = etiqueta("- MMSI " + barco.getMmsi());
        Label lblPos = etiqueta("- Lat: " + coordenada(barco.getLat()) + "  Lon: " + coordenada(barco.getLon()));
        Label lblFecha = etiqueta("- Ultima vez visto: " + fecha);

        VBox bloque = new VBox(2, lblNombre, lblMmsi, lblPos, lblFecha);
        bloque.setStyle(ESTILO_BLOQUE);
        bloque.setOnMouseClicked(e -> seleccionarBarco(barco.getMmsi(), true));
        return bloque;
    }

    private Label etiqueta(String texto) {
        Label label = new Label(texto);
        label.setWrapText(true);
        return label;
    }

    private String coordenada(Double valor) {
        return valor == null ? "?" : String.format(Locale.ROOT, "%.5f", valor);
    }

    // ---------- Seleccion en los dos sentidos ----------

    /**
     * avisarAlMapa = true  -> el click fue en la lista: se le pide al mapa que centre el barco.
     * avisarAlMapa = false -> el click fue en el mapa: el mapa ya se marco solo; falta marcar y mostrar el bloque.
     */
    private void seleccionarBarco(int mmsi, boolean avisarAlMapa) {
        mmsiSeleccionado = mmsi;
        marcarBloque(mmsi);
        if (avisarAlMapa) {
            if (mapaListo) ventanaMapa().call("seleccionar", mmsi, false);
        } else {
            desplazarListaHasta(mmsi);
        }
    }

    private void marcarBloque(int mmsi) {
        bloques.forEach((clave, bloque) ->
                bloque.setStyle(clave == mmsi ? ESTILO_BLOQUE_SELECCIONADO : ESTILO_BLOQUE));
    }

    private void desplazarListaHasta(int mmsi) {
        VBox bloque = bloques.get(mmsi);
        if (bloque == null) return;
        // runLater: espera a que JavaFX termine de calcular tamanos antes de medir.
        Platform.runLater(() -> {
            double rango = listaBarcos.getHeight() - scrollLista.getViewportBounds().getHeight();
            if (rango <= 0) return;
            double y = bloque.getBoundsInParent().getMinY();
            scrollLista.setVvalue(Math.max(0, Math.min(1, y / rango)));
        });
    }

    // ================= Pestana de grabaciones =================

    private void configurarGrabaciones() {
        // Se usan lambdas para decirle a cada columna que texto mostrar de cada AudioModel.
        colFecha.setCellValueFactory(c -> new SimpleStringProperty(formatearFecha(c.getValue().getFechaCreacion())));
        colNombre.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getNombre()));
        // La columna de nombre ocupa lo que sobre del ancho de la tabla
        colNombre.prefWidthProperty().bind(tablaAudios.widthProperty().subtract(colFecha.widthProperty()).subtract(20));

        // Doble click en una fila = reproducir esa grabacion
        tablaAudios.setRowFactory(tabla -> {
            TableRow<AudioModel> fila = new TableRow<>();
            fila.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !fila.isEmpty()) {
                    reproducir(fila.getItem());
                }
            });
            return fila;
        });

        // La lista se actualiza cada vez que se entra a la pestana
        tab_grabaciones.setOnSelectionChanged(e -> {
            if (tab_grabaciones.isSelected()) cargarAudios();
        });

        // Mover o hacer click en la barra de progreso = saltar a ese momento del audio.
        // Mientras el usuario la sostiene, el reproductor no la mueve (arrastrandoSlider).
        sliderProgreso.setOnMousePressed(e -> arrastrandoSlider = true);
        sliderProgreso.setOnMouseReleased(e -> {
            arrastrandoSlider = false;
            if (reproductor != null) reproductor.seek(Duration.seconds(sliderProgreso.getValue()));
        });

        cargarAudios();
    }

    @FXML
    private void actualizarAudios() {
        cargarAudios();
    }

    private void cargarAudios() {
        Task<String> tarea = new Task<>() {
            @Override
            protected String call() {
                return api.getJSONAudios();
            }
        };
        tarea.setOnSucceeded(e -> mostrarAudios(tarea.getValue()));
        tarea.setOnFailed(e -> System.err.println("No se pudo cargar las grabaciones: " + tarea.getException()));
        iniciarHilo(tarea, "carga-audios");
    }

    private void mostrarAudios(String json) {
        if (json == null || json.isBlank()) {
            System.err.println("Sin respuesta del backend al pedir las grabaciones.");
            return;
        }
        List<AudioModel> audios;
        try {
            audios = mapper.readValue(json, new TypeReference<List<AudioModel>>() {});
        } catch (JsonProcessingException ex) {
            System.err.println("JSON de audios invalido: " + ex.getMessage());
            return;
        }
        AudioModel previo = tablaAudios.getSelectionModel().getSelectedItem();
        Integer idPrevio = (previo == null) ? null : previo.getId();

        tablaAudios.getItems().setAll(audios);

        // Se vuelve a seleccionar la misma grabacion si sigue en la lista
        if (idPrevio != null) {
            for (AudioModel audio : audios) {
                if (idPrevio.equals(audio.getId())) {
                    tablaAudios.getSelectionModel().select(audio);
                    break;
                }
            }
        }
    }

    // ---------- Reproductor ----------

    @FXML
    private void alternarReproduccion() {
        AudioModel seleccionado = tablaAudios.getSelectionModel().getSelectedItem();
        boolean mismoAudio = seleccionado == null || seleccionado.getId().equals(idCargado);

        if (reproductor != null && mismoAudio) {
            // Ya hay un audio cargado: se alterna pausa / reproduccion
            if (reproductor.getStatus() == MediaPlayer.Status.PLAYING) reproductor.pause();
            else reproductor.play();
        } else if (seleccionado != null) {
            reproducir(seleccionado);
        }
    }

    @FXML
    private void detenerReproduccion() {
        if (reproductor != null) {
            reproductor.stop();
            sliderProgreso.setValue(0);
        }
    }

    private void reproducir(AudioModel audio) {
        liberarReproductor();
        lblAudioActual.setText("Descargando " + audio.getNombre() + " ...");

        // Si el usuario elige otra grabacion mientras esta se descarga, el resultado viejo se descarta.
        final int solicitud = ++contadorSolicitudes;

        Task<Path> tarea = new Task<>() {
            @Override
            protected Path call() throws IOException {
                return obtenerArchivo(audio.getId());
            }
        };
        tarea.setOnSucceeded(e -> {
            if (solicitud != contadorSolicitudes) return;

            Path archivo = tarea.getValue();
            if (archivo == null) {
                lblAudioActual.setText("No se pudo obtener el audio (revisa que el backend este corriendo)");
            } else {
                iniciarReproduccion(audio, archivo);
            }
        });
        tarea.setOnFailed(e -> {
            if (solicitud != contadorSolicitudes) return;

            lblAudioActual.setText("Error al descargar el audio");
            System.err.println("Error al descargar el audio: " + tarea.getException());
        });
        iniciarHilo(tarea, "descarga-audio");
    }

    /** Pide el .wav al backend y lo guarda en una carpeta temporal. Corre en un hilo aparte. */
    private synchronized Path obtenerArchivo(int id) throws IOException {
        Path guardado = audiosDescargados.get(id);
        if (guardado != null && Files.exists(guardado)) {
            return guardado;   // ya se habia descargado antes
        }
        if (carpetaTemporal == null) {
            carpetaTemporal = Files.createTempDirectory("naviwave-audios");
            carpetaTemporal.toFile().deleteOnExit();
        }
        Path destino = carpetaTemporal.resolve("audio_" + id + ".wav");
        if (!api.descargarAudio(id, destino)) {
            return null;
        }
        destino.toFile().deleteOnExit();
        audiosDescargados.put(id, destino);
        return destino;
    }

    private void iniciarReproduccion(AudioModel audio, Path archivo) {
        try {
            Media media = new Media(archivo.toUri().toString());
            reproductor = new MediaPlayer(media);
        } catch (RuntimeException ex) {
            lblAudioActual.setText("No se pudo abrir el audio: " + ex.getMessage());
            return;
        }
        idCargado = audio.getId();
        lblAudioActual.setText(audio.getNombre());

        MediaPlayer jugador = reproductor;
        jugador.setOnReady(() -> {
            sliderProgreso.setDisable(false);
            sliderProgreso.setMax(jugador.getMedia().getDuration().toSeconds());
            sliderProgreso.setValue(0);
            actualizarTiempo(Duration.ZERO);
            jugador.play();
        });
        jugador.currentTimeProperty().addListener((obs, anterior, tiempo) -> {
            if (!arrastrandoSlider) sliderProgreso.setValue(tiempo.toSeconds());
            actualizarTiempo(tiempo);
        });
        jugador.statusProperty().addListener((obs, anterior, estado) ->
                btnPlayPausa.setText(estado == MediaPlayer.Status.PLAYING ? "Pausar" : "Reproducir"));
        jugador.setOnEndOfMedia(() -> {
            jugador.stop();
            sliderProgreso.setValue(0);
        });
        jugador.setOnError(() -> lblAudioActual.setText("Error de audio: " + jugador.getError().getMessage()));
    }

    /** Detiene y libera el reproductor actual (el MediaPlayer usa recursos del sistema). */
    private void liberarReproductor() {
        if (reproductor != null) {
            reproductor.stop();
            reproductor.dispose();
            reproductor = null;
        }
        idCargado = null;
        btnPlayPausa.setText("Reproducir");
        sliderProgreso.setValue(0);
        sliderProgreso.setDisable(true);
        lblTiempo.setText("--:-- / --:--");
    }

    private void actualizarTiempo(Duration actual) {
        Duration total = (reproductor == null) ? Duration.UNKNOWN : reproductor.getTotalDuration();
        lblTiempo.setText(formatoTiempo(actual) + " / " + formatoTiempo(total));
    }

    private String formatoTiempo(Duration duracion) {
        if (duracion == null || duracion.isUnknown() || duracion.isIndefinite()) return "--:--";
        int segundos = (int) duracion.toSeconds();
        return String.format("%02d:%02d", segundos / 60, segundos % 60);
    }

    private String formatearFecha(Timestamp fecha) {
        return (fecha == null) ? "?" : fecha.toLocalDateTime().format(FORMATO_FECHA);
    }

    private void iniciarHilo(Task<?> tarea, String nombre) {
        Thread hilo = new Thread(tarea, nombre);
        hilo.setDaemon(true);
        hilo.start();
    }
}