package com.slyblasco.naviwave.audio;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** Pantallas que escuchan (ws://.../ws/audio-vivo/oyente). Solo reciben; lo que manden se ignora. */
@Component
public class OyenteHandler extends BinaryWebSocketHandler {

    private final AudioVivoHub hub;

    public OyenteHandler(AudioVivoHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) {
        hub.agregarOyente(sesion);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        hub.quitarOyente(sesion);
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        hub.quitarOyente(sesion);
    }
}
