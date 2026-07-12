package com.slyblasco.naviwave.controllers;

import com.slyblasco.naviwave.models.BarcoModel;
import com.slyblasco.naviwave.repository.BarcoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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


    @GetMapping("/24hours")
    public List<BarcoModel> obtenerBarcos24Horas() {
        LocalDateTime hace24horas = LocalDateTime.now().minusHours(24);

        DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String fechaTexto = hace24horas.format(formato);

        System.out.println("=== DEPURACIÓN SQLITE ===");
        System.out.println("Texto enviado a BD: " + fechaTexto);

        return barcoRepository.findRecientesSQLite(fechaTexto);
    }

}

