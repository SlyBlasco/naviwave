package com.slyblasco.naviwave.repository;

import com.slyblasco.naviwave.models.AudioModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioRepository extends JpaRepository<AudioModel, Integer> {
}
