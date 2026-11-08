package com.slyblasco.frontend.api;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Direccion del backend. Se lee del archivo naviwave.properties (en la carpeta desde donde se
 * ejecuta la pantalla). Si el archivo no existe, se usa localhost:8080.
 *
 * Ejemplo del contenido de naviwave.properties:
 *     servidor=192.168.1.50:8080
 */
public final class ConfiguracionServidor {

    private static final Path ARCHIVO = Path.of("naviwave.properties");
    private static final String POR_DEFECTO = "localhost:8080";

    // Se lee una sola vez, al cargar la clase
    private static final String SERVIDOR = leerServidor();

    private ConfiguracionServidor() {
    }

    /** Por ejemplo: http://192.168.1.50:8080 */
    public static String httpBase() {
        return "http://" + SERVIDOR;
    }

    /** Por ejemplo: ws://192.168.1.50:8080 */
    public static String wsBase() {
        return "ws://" + SERVIDOR;
    }

    private static String leerServidor() {
        String valor = POR_DEFECTO;
        if (Files.isRegularFile(ARCHIVO)) {
            Properties propiedades = new Properties();
            try (Reader lector = Files.newBufferedReader(ARCHIVO, StandardCharsets.UTF_8)) {
                propiedades.load(lector);
                valor = limpiar(propiedades.getProperty("servidor", POR_DEFECTO));
            } catch (IOException e) {
                System.err.println("No se pudo leer " + ARCHIVO.toAbsolutePath() + ": " + e.getMessage());
            }
        } else {
            System.out.println("No hay " + ARCHIVO.toAbsolutePath() + ": se usa " + POR_DEFECTO);
        }
        System.out.println("Servidor del backend: " + valor);
        return valor;
    }

    /** Deja solo "host:puerto": quita espacios, un "http://" por error y la barra final. */
    private static String limpiar(String texto) {
        String limpio = texto.trim();
        if (limpio.toLowerCase().startsWith("https://") || limpio.toLowerCase().startsWith("wss://")) {
            System.err.println("Aviso: todavia no hay soporte para HTTPS; se usara HTTP normal.");
        }
        limpio = limpio.replaceFirst("(?i)^(https?|wss?)://", "");
        while (limpio.endsWith("/")) {
            limpio = limpio.substring(0, limpio.length() - 1);
        }
        return limpio.isEmpty() ? POR_DEFECTO : limpio;
    }
}
