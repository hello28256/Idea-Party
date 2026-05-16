package com.ideaparty.repository;

import com.ideaparty.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    boolean existsByEmailAndIdNot(String email, UUID id);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) = LOWER(:keyword) OR LOWER(u.email) = LOWER(:keyword)")
    Optional<User> findByUsernameOrEmail(@Param("keyword") String keyword);
}
