package com.slyblasco.frontend.api;

public class ApiCalls {

    HttpSync api = new HttpSync();

    public String getJSONBarcos24Horas(){
        return api.apiRequest("http://localhost:8080/api/barcos/24hours");
    }

    public String getJSONTodosBarcos(){
        return api.apiRequest("http://localhost:8080/api/barcos");
    }
}
