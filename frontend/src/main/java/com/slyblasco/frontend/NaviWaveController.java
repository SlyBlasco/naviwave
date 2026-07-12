package com.slyblasco.frontend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slyblasco.frontend.api.ApiCalls;
import com.slyblasco.frontend.models.BarcoModel;
import javafx.fxml.FXML;

import java.util.List;

public class NaviWaveController {
    ApiCalls api = new ApiCalls();
    ObjectMapper mapper = new ObjectMapper();

    @FXML
    public void initialize() throws JsonProcessingException {
        List<BarcoModel> objetos = mapper.readValue(api.getJSONBarcos24Horas(), new TypeReference<List<BarcoModel>>() {});
    }
}
