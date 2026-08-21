package com.trova.backend.repository;

import com.trova.backend.entity.SavedPlace;
import com.trova.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, Long> {
    List<SavedPlace> findByUserOrderByCreatedAtDescIdDesc(User user);
    Optional<SavedPlace> findByIdAndUser(Long id, User user);
    void deleteByUser(User user);
}
