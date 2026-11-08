package com.slyblasco.frontend.api;

import java.nio.file.Path;

public class ApiCalls {

    HttpSync api = new HttpSync();

    public String getJSONBarcos24Horas(){
        return api.apiRequest(ConfiguracionServidor.httpBase() + "/api/barcos/24hours");
    }

    public String getJSONTodosBarcos(){
        return api.apiRequest(ConfiguracionServidor.httpBase() + "/api/barcos");
    }

    public String getJSONAudios(){
        return api.apiRequest(ConfiguracionServidor.httpBase() + "/api/audios");
    }

    /** Descarga el .wav de la grabacion con ese id a "destino". Devuelve true si salio bien. */
    public boolean descargarAudio(int id, Path destino){
        return api.apiDownload(ConfiguracionServidor.httpBase() + "/api/audios/" + id + "/archivo", destino);
    }
}
