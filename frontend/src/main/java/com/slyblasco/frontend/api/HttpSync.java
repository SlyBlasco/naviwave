package com.slyblasco.frontend.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpSync {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String apiRequest(String url) {

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200){
                return response.body();
            } else {
                System.err.println("El servidor respondio con codigo: " + response.statusCode());
                return "";
            }
        } catch (IOException e) {
            System.err.println("Error de network: ");
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("La solicitud fue interrumpida: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
        return "";
    }
}
