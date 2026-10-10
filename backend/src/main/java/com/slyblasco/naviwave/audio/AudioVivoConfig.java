package com.slyblasco.naviwave.audio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;


/** Registra las dos direcciones WebSocket del audio en vivo. */
@Configuration
@EnableWebSocket
public class AudioVivoConfig implements WebSocketConfigurer {

    private final EmisorHandler emisorHandler;
    private final OyenteHandler oyenteHandler;

    public AudioVivoConfig(EmisorHandler emisorHandler, OyenteHandler oyenteHandler) {
        this.emisorHandler = emisorHandler;
        this.oyenteHandler = oyenteHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registro) {
        registro.addHandler(emisorHandler, "/ws/audio-vivo/emisor");
        registro.addHandler(oyenteHandler, "/ws/audio-vivo/oyente");
    }

    // Por defecto el servidor solo acepta mensajes binarios de hasta 8 KB; los nuestros son de ~6 KB.
    // Se sube el limite para tener margen si algun dia se cambia el tamano del pedazo.
    @Bean
    public ServletServerContainerFactoryBean configurarContenedorWebSocket() {
        ServletServerContainerFactoryBean contenedor = new ServletServerContainerFactoryBean();
        contenedor.setMaxBinaryMessageBufferSize(64 * 1024);
        return contenedor;
    }
}
