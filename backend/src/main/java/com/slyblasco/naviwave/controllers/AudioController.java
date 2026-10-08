package com.slyblasco.naviwave.controllers;

import com.slyblasco.naviwave.models.AudioModel;
import com.slyblasco.naviwave.repository.AudioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/audios")
public class AudioController {
    @Autowired
    private AudioRepository audioRepository;

    // Mas recientes primero
    @GetMapping
    public List<AudioModel> obtenerTodosAudios() {
        return audioRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    // GET /api/audios/5/archivo -> entrega el .wav de la grabacion con id 5
    @GetMapping("/{id}/archivo")
    public ResponseEntity<Resource> obtenerArchivo(@PathVariable Integer id) {
        // El cliente solo manda un id. La ruta sale de la base de datos (la escribio Python),
        // nunca de lo que mande el cliente: asi nadie puede pedir otros archivos del disco.
        Optional<AudioModel> audio = audioRepository.findById(id);
        if (audio.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path ruta = Path.of(audio.get().getRutaArchivo()).normalize();
        boolean esWav = ruta.toString().toLowerCase().endsWith(".wav");
        if (!esWav || !Files.isRegularFile(ruta)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(new FileSystemResource(ruta));
    }
}
