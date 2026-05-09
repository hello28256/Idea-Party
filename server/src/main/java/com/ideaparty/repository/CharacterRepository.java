package com.ideaparty.repository;

import com.ideaparty.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CharacterRepository extends JpaRepository<Character, UUID> {

    List<Character> findByOwnerId(UUID ownerId);

    List<Character> findByIsPresetTrue();

    Optional<Character> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);
}
