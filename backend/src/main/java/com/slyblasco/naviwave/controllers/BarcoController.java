package com.slyblasco.naviwave.controllers;

import com.slyblasco.naviwave.models.BarcoModel;
import com.slyblasco.naviwave.repository.BarcoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/barcos")
public class BarcoController {
    @Autowired
    private BarcoRepository barcoRepository;

    @GetMapping
    public List<BarcoModel> obtenerTodosBarcos() {
        return barcoRepository.findAll();
    }

}

