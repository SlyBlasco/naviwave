package com.slyblasco.naviwave.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.sql.Timestamp;

@Entity
@Table(name = "audios")
public class AudioModel {
    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "ruta_archivo")
    private String rutaArchivo;

    @Column(name = "fecha_creacion")
    private Timestamp fechaCreacion;

    //Constructor vacio
    public AudioModel() {}

    //Getter y Setters
    public Integer getId() {return id;}
    public void setId(Integer id) {this.id = id;}

    public String getNombre() {return nombre;}
    public void setNombre(String nombre) {this.nombre = nombre;}

    // Se usa dentro del servidor, pero no se envia en el JSON: no hay por que mostrar rutas del disco.
    @JsonIgnore
    public String getRutaArchivo() {return rutaArchivo;}

    public Timestamp getFechaCreacion() {return fechaCreacion;}
}