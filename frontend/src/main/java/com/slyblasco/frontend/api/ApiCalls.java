package com.slyblasco.frontend.api;

import java.nio.file.Path;

public class ApiCalls {

    HttpSync api = new HttpSync();

    public String getJSONBarcos24Horas(){
        return api.apiRequest("http://localhost:8080/api/barcos/24hours");
    }

    public String getJSONTodosBarcos(){
        return api.apiRequest("http://localhost:8080/api/barcos");
    }

    public String getJSONAudios(){
        return api.apiRequest("http://localhost:8080/api/audios");
    }

    /** Descarga el .wav de la grabacion con ese id a "destino". Devuelve true si salio bien. */
    public boolean descargarAudio(int id, Path destino){
        return api.apiDownload("http://localhost:8080/api/audios/" + id + "/archivo", destino);
    }
}