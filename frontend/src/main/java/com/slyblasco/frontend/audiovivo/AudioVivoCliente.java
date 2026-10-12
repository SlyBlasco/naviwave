package com.slyblasco.frontend.audiovivo;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Escucha el audio en vivo que reparte el backend (WebSocket) y lo reproduce por las bocinas.
 *
 * Camino del audio:  WebSocket -> cola -> hilo de reproduccion -> tarjeta de sonido
 *
 * Los avisos (estado, nivel, error) se llaman desde hilos de fondo: quien los reciba
 * y toque la interfaz debe pasarlos antes por Platform.runLater.
 */
public class AudioVivoCliente {

    public static final String URL_POR_DEFECTO = "ws://localhost:8080/ws/audio-vivo/oyente";

    public enum Estado { CONECTANDO, ESPERANDO_AUDIO, ESCUCHANDO, DESCONECTADO, ERROR }

    /** La "bocina". Es una interfaz para poder separar la red del sonido (y probar cada una por su lado). */
    public interface Salida {
        void abrir() throws Exception;
        void escribir(byte[] datos, int largo);   // puede bloquearse hasta que haya lugar en la bocina
        void cerrar();
    }

    // Formato del audio (debe coincidir con lo que manda el backend): 48 kHz, 16 bits con signo, mono, little-endian
    private static final AudioFormat FORMATO = new AudioFormat(48_000f, 16, 1, true, false);
    private static final int BYTES_POR_SEGUNDO = 48_000 * 2;

    private static final int PEDAZOS_PARA_EMPEZAR = 3;    // ~190 ms de colchon antes de sonar
    private static final int MAX_PEDAZOS_EN_COLA = 30;    // ~1.9 s; si se llena se tira lo mas viejo
    private static final int MS_SIN_AUDIO_PARA_PAUSA = 250;

    private final URI uri;
    private final Consumer<Estado> alCambiarEstado;
    private final DoubleConsumer alCambiarNivel;
    private final Consumer<String> alFallar;
    private final Supplier<Salida> fabricaSalida;

    private final BlockingQueue<byte[]> cola = new ArrayBlockingQueue<>(MAX_PEDAZOS_EN_COLA);
    private volatile double volumen = 1.0;
    private volatile boolean activo = false;

    private WebSocket webSocket;
    private Thread hiloReproduccion;
    private Salida salida;

    public AudioVivoCliente(URI uri, Consumer<Estado> alCambiarEstado, DoubleConsumer alCambiarNivel,
                            Consumer<String> alFallar) {
        this(uri, alCambiarEstado, alCambiarNivel, alFallar, SalidaSourceDataLine::new);
    }

    public AudioVivoCliente(URI uri, Consumer<Estado> alCambiarEstado, DoubleConsumer alCambiarNivel,
                            Consumer<String> alFallar, Supplier<Salida> fabricaSalida) {
        this.uri = uri;
        this.alCambiarEstado = alCambiarEstado;
        this.alCambiarNivel = alCambiarNivel;
        this.alFallar = alFallar;
        this.fabricaSalida = fabricaSalida;
    }

    public boolean isActivo() {
        return activo;
    }

    /** Volumen de 0.0 (silencio) a 1.0 (original). */
    public void setVolumen(double volumen) {
        this.volumen = Math.max(0.0, Math.min(1.0, volumen));
    }

    public synchronized void conectar() {
        if (activo) return;
        activo = true;
        cola.clear();
        alCambiarEstado.accept(Estado.CONECTANDO);

        try {
            salida = fabricaSalida.get();
            salida.abrir();
        } catch (Exception e) {
            fallar("No se pudo abrir la salida de audio: " + e.getMessage());
            return;
        }

        final Salida salidaActual = salida;
        hiloReproduccion = new Thread(() -> reproducir(salidaActual), "audio-vivo-reproduccion");
        hiloReproduccion.setDaemon(true);
        hiloReproduccion.start();

        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, new Escucha())
                .exceptionally(error -> {
                    fallar("No se pudo conectar al backend: " + causa(error));
                    return null;
                });
    }

    public synchronized void desconectar() {
        terminar(Estado.DESCONECTADO);
    }

    // ---------------- WebSocket ----------------

    private class Escucha implements WebSocket.Listener {
        // Un mensaje grande puede llegar en trozos; se juntan hasta que llega el ultimo
        private final ByteArrayOutputStream parcial = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            synchronized (AudioVivoCliente.this) {
                if (!activo) {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "cancelado");
                    return;
                }
                webSocket = ws;
            }
            alCambiarEstado.accept(Estado.ESPERANDO_AUDIO);
            ws.request(1);   // hay que pedir explicitamente cada mensaje
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer datos, boolean ultimo) {
            byte[] trozo = new byte[datos.remaining()];
            datos.get(trozo);
            parcial.write(trozo, 0, trozo.length);
            if (ultimo) {
                encolar(parcial.toByteArray());
                parcial.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int codigo, String razon) {
            desconectar();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            fallar("Se perdio la conexion: " + causa(error));
        }
    }

    private void encolar(byte[] pedazo) {
        if (!activo) return;
        if (pedazo.length % 2 != 0) {
            pedazo = Arrays.copyOf(pedazo, pedazo.length - 1);   // cada muestra son 2 bytes
        }
        // Si la cola esta llena, se tira lo mas viejo: es mejor perder un poco que acumular retraso
        while (!cola.offer(pedazo)) {
            cola.poll();
        }
    }

    // ---------------- Reproduccion ----------------

    private void reproducir(Salida bocina) {
        boolean enTransmision = false;
        try {
            while (activo) {
                byte[] pedazo = cola.poll(MS_SIN_AUDIO_PARA_PAUSA, TimeUnit.MILLISECONDS);

                if (pedazo == null) {                       // no esta llegando audio
                    if (enTransmision) {
                        enTransmision = false;
                        alCambiarNivel.accept(0);
                        alCambiarEstado.accept(Estado.ESPERANDO_AUDIO);
                    }
                    continue;
                }

                if (!enTransmision) {
                    // Empieza una transmision: se junta un poco de audio antes de sonar para que no se corte
                    long limite = System.nanoTime() + 300_000_000L;
                    while (cola.size() < PEDAZOS_PARA_EMPEZAR - 1 && System.nanoTime() < limite) {
                        Thread.sleep(10);
                    }
                    enTransmision = true;
                    alCambiarEstado.accept(Estado.ESCUCHANDO);
                }

                aplicarVolumenYMedir(pedazo);
                bocina.escribir(pedazo, pedazo.length);     // se bloquea si la bocina esta llena: eso marca el ritmo
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (activo) fallar("Error al reproducir: " + e.getMessage());
        }
    }

    /** Baja el volumen del pedazo (modificandolo) y avisa el nivel de la senal original (0 a 1). */
    private void aplicarVolumenYMedir(byte[] pedazo) {
        ByteBuffer buffer = ByteBuffer.wrap(pedazo).order(ByteOrder.LITTLE_ENDIAN);
        double factor = volumen;
        int muestras = pedazo.length / 2;
        double sumaCuadrados = 0;
        for (int i = 0; i < muestras; i++) {
            short muestra = buffer.getShort(2 * i);
            double valor = muestra / 32768.0;
            sumaCuadrados += valor * valor;
            buffer.putShort(2 * i, (short) Math.round(muestra * factor));
        }
        if (muestras > 0) {
            alCambiarNivel.accept(Math.sqrt(sumaCuadrados / muestras));   // nivel RMS
        }
    }

    // ---------------- Cierre ----------------

    private void fallar(String mensaje) {
        alFallar.accept(mensaje);
        synchronized (this) {
            terminar(Estado.ERROR);
        }
    }

    private synchronized void terminar(Estado estadoFinal) {
        boolean estabaActivo = activo;
        activo = false;

        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "adios");
        }
        if (hiloReproduccion != null) {
            hiloReproduccion.interrupt();
            hiloReproduccion = null;
        }
        if (salida != null) {
            salida.cerrar();
            salida = null;
        }
        cola.clear();

        if (estabaActivo) {
            alCambiarNivel.accept(0);
            alCambiarEstado.accept(estadoFinal);
        }
    }

    private static String causa(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ---------------- Bocina real ----------------

    private static class SalidaSourceDataLine implements Salida {
        private SourceDataLine linea;

        @Override
        public void abrir() throws LineUnavailableException {
            linea = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, FORMATO));
            linea.open(FORMATO, BYTES_POR_SEGUNDO * 400 / 1000);   // buffer interno de ~400 ms
            linea.start();
        }

        @Override
        public void escribir(byte[] datos, int largo) {
            linea.write(datos, 0, largo);
        }

        @Override
        public void cerrar() {
            if (linea != null) {
                linea.stop();
                linea.flush();
                linea.close();
                linea = null;
            }
        }
    }
}
