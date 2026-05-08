package com.ideaparty.repository;

import com.ideaparty.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<Character, String> {

    @Query("SELECT c FROM Character c JOIN c.expertise e WHERE e = :expertise")
    List<Character> findByExpertise(@Param("expertise") String expertise);

    List<Character> findByNameContainingIgnoreCase(String name);
}
