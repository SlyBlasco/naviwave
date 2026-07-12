package com.slyblasco.naviwave.repository;

import com.slyblasco.naviwave.models.BarcoModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface BarcoRepository extends JpaRepository<BarcoModel, Integer> {
    @Query("SELECT b FROM BarcoModel b WHERE strftime('%Y-%m-%d %H:%M:%S', b.fechaCreacion) > :fechaTexto ORDER BY b.fechaCreacion DESC")
    List<BarcoModel> findRecientesSQLite(@Param("fechaTexto") String fechaTexto);
}
