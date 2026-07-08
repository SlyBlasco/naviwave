package com.slyblasco.naviwave.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "barcos")
public class BarcoModel {
    @Id
    @Column(name = "mmsi")
    private Integer mmsi;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lon")
    private Double lon;

    //Constructor vacio
    public BarcoModel() {}

    //Getters y Setters
    public Integer getMmsi() {return mmsi;}
    public void setMmsi(Integer mmsi) {this.mmsi = mmsi;}

    public String getNombre() {return nombre;}
    public void setNombre(String nombre) {this.nombre = nombre;}

    public Double getLat() {return lat;}
    public void setLat(Double lat) {this.lat = lat;}

    public Double getLon() {return lon;}
    public void setLon(Double lon) {this.lon = lon;}
}
