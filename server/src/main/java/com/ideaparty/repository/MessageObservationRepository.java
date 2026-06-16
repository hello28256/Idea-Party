package com.ideaparty.repository;

import com.ideaparty.entity.MessageObservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageObservationRepository
        extends JpaRepository<MessageObservation, String>, JpaSpecificationExecutor<MessageObservation> {

    Page<MessageObservation> findAll(Specification<MessageObservation> spec, Pageable pageable);
}
