package com.slyblasco.frontend.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioModel {

    private Integer id;

    private String nombre;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private Timestamp fechaCreacion;

    //Constructor vacio
    public AudioModel() {}

    //Getters y Setters
    public Integer getId() {return id;}
    public void setId(Integer id) {this.id = id;}

    public String getNombre() {return nombre;}
    public void setNombre(String nombre) {this.nombre = nombre;}

    public Timestamp getFechaCreacion() {return fechaCreacion;}
    public void setFechaCreacion(Timestamp fechaCreacion) {this.fechaCreacion = fechaCreacion;}
}
