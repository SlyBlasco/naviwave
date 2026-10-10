package com.slyblasco.naviwave.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punto de reparto del audio en vivo: recibe pedazos de audio del emisor (Python)
 * y se los reenvia a todas las pantallas que estan escuchando.
 *
 * Formato del audio que viaja (lo define main.py, y lo asume la pantalla):
 * PCM de 16 bits con signo, mono, 48 000 muestras por segundo, little-endian.
 * Cada mensaje trae un pedazo de ~64 ms (3072 muestras = 6144 bytes).
 */
@Component
public class AudioVivoHub {

    private static final Logger log = LoggerFactory.getLogger(AudioVivoHub.class);

    // id de la conexion -> conexion del oyente
    private final Map<String, WebSocketSession> oyentes = new ConcurrentHashMap<>();

    public void agregarOyente(WebSocketSession sesion) {
        // El "decorador" hace dos cosas: permite enviar desde varios hilos sin romperse
        // y corta a un oyente que va demasiado lento (2 s sin poder enviarle o 512 KB acumulados),
        // para que uno lento no atrase a los demas.
        oyentes.put(sesion.getId(), new ConcurrentWebSocketSessionDecorator(sesion, 2_000, 512 * 1024));
        log.info("Oyente conectado. Oyentes: {}", oyentes.size());
    }

    public void quitarOyente(WebSocketSession sesion) {
        if (oyentes.remove(sesion.getId()) != null) {
            log.info("Oyente desconectado. Oyentes: {}", oyentes.size());
        }
    }

    /** Reenvia un pedazo de audio a todos los oyentes. Si no hay ninguno, se descarta. */
    public void difundir(ByteBuffer pedazo) {
        if (oyentes.isEmpty()) {
            return;
        }
        byte[] datos = new byte[pedazo.remaining()];
        pedazo.get(datos);

        for (WebSocketSession oyente : oyentes.values()) {
            try {
                if (oyente.isOpen()) {
                    // Un BinaryMessage nuevo por oyente: cada envio consume su propio buffer
                    oyente.sendMessage(new BinaryMessage(datos));
                } else {
                    oyentes.remove(oyente.getId());
                }
            } catch (Exception e) {
                log.warn("Se quita un oyente por error al enviarle: {}", e.getMessage());
                oyentes.remove(oyente.getId());
                try {
                    oyente.close();
                } catch (IOException ignorado) {
                    // ya estaba cerrado
                }
            }
        }
    }
}
