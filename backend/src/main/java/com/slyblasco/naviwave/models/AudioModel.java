package com.slyblasco.naviwave.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.sql.Date;

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
    private Date fechaCreacion;
    
    //Constructor vacio
    public AudioModel() {}
    
    //Getter y Setters
    public Integer getId() {return id;}
    public void setId(Integer id) {this.id = id;}
    
    public String getNombre() {return nombre;}
    public void setNombre(String nombre) {this.nombre = nombre;}
    
    public String getRutaArchivo() {return rutaArchivo;}

    public Date getFechaCreacion() {return fechaCreacion;}
}
