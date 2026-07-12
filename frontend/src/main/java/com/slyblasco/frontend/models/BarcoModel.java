package com.slyblasco.frontend.models;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.sql.Timestamp;

public class BarcoModel {

    private Integer mmsi;

    private String tipo;

    private String nombre;

    private Double lat;

    private Double lon;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private Timestamp fechaCreacion;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    private Timestamp fechaActualizada;

    //Constructor vacio
    public BarcoModel() {}

    //Getters y Setters
    public Integer getMmsi() {return mmsi;}
    public void setMmsi(Integer mmsi) {this.mmsi = mmsi;}

    public String getTipo() {return tipo;}
    public void setTipo(String tipo) {this.tipo = tipo;}

    public String getNombre() {return nombre;}
    public void setNombre(String nombre) {this.nombre = nombre;}

    public Double getLat() {return lat;}
    public void setLat(Double lat) {this.lat = lat;}

    public Double getLon() {return lon;}
    public void setLon(Double lon) {this.lon = lon;}

    public Timestamp getFechaCreacion() {return fechaCreacion;}
    public void setFechaCreacion(Timestamp fechaCreacion) {this.fechaCreacion = fechaCreacion;}

    public Timestamp getFechaActualizada() { return fechaActualizada; }
    public void setFechaActualizada(Timestamp fechaActualizada) { this.fechaActualizada = fechaActualizada; }
}
