package com.slyblasco.naviwave.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Deja pasar solo las conexiones que vienen de esta misma computadora.
 * Se usa en la puerta del emisor: quien manda el audio (main.py) corre junto al backend,
 * asi que nadie mas de la red necesita (ni debe poder) colar audio.
 *
 * OJO: si algun dia se pone un proxy inverso (por ejemplo para HTTPS) en esta misma maquina,
 * TODAS las conexiones parecerian locales y esta revision ya no protegeria nada;
 * en ese caso hay que bloquear la ruta del emisor en el proxy.
 */
public class SoloConexionesLocales implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SoloConexionesLocales.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest peticion, ServerHttpResponse respuesta,
                                   WebSocketHandler manejador, Map<String, Object> atributos) {
        InetSocketAddress remota = peticion.getRemoteAddress();
        InetSocketAddress local = peticion.getLocalAddress();

        boolean esLocal = remota != null && (
                remota.getAddress().isLoopbackAddress()                       // 127.0.0.1 o ::1
                || (local != null && remota.getAddress().equals(local.getAddress())));   // se conecta a su propia IP

        if (!esLocal) {
            InetAddress origen = (remota == null) ? null : remota.getAddress();
            log.warn("Conexion rechazada al emisor de audio: viene de {}", origen);
            respuesta.setStatusCode(HttpStatus.FORBIDDEN);
        }
        return esLocal;
    }

    @Override
    public void afterHandshake(ServerHttpRequest peticion, ServerHttpResponse respuesta,
                               WebSocketHandler manejador, Exception excepcion) {
        // nada que hacer
    }
}
