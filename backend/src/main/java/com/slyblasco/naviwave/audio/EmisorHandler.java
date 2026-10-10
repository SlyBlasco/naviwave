package com.slyblasco.naviwave.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** Recibe el audio que manda Python (ws://.../ws/audio-vivo/emisor) y lo pasa al hub. */
@Component
public class EmisorHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EmisorHandler.class);

    private final AudioVivoHub hub;

    public EmisorHandler(AudioVivoHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        log.info("Emisor de audio conectado");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession sesion, BinaryMessage mensaje) {
        hub.difundir(mensaje.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        log.info("Emisor de audio desconectado");
    }
}
