package com.slyblasco.naviwave.repository;

import com.slyblasco.naviwave.models.BarcoModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BarcoRepository extends JpaRepository<BarcoModel, Integer> {

}
