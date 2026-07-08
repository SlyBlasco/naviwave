package com.slyblasco.naviwave.controllers;

import com.slyblasco.naviwave.models.AudioModel;
import com.slyblasco.naviwave.repository.AudioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audios")
public class AudioController {
    @Autowired
    private AudioRepository audioRepository;

    @GetMapping
    public List<AudioModel> obtenerTodosAudios() {
        return audioRepository.findAll();
    }
}
