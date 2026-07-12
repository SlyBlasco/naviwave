package com.slyblasco.naviwave.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.sql.Date;
import java.sql.Timestamp;

@Entity
@Table(name = "barcos")
public class BarcoModel {
    @Id
    @Column(name = "mmsi")
    private Integer mmsi;

    @Column(name = "tipo")
    private String tipo;

    @Column(name = "nombre")
    private String nombre;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lon")
    private Double lon;

    @Column(name = "fecha_creacion")
    private Timestamp fechaCreacion;

    @Column(name = "fecha_actualizada")
    private Date fechaActualizada;

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

    public Date getFechaActualizada() { return fechaActualizada; }
    public void setFechaActualizada(Date fechaActualizada) { this.fechaActualizada = fechaActualizada; }
}
