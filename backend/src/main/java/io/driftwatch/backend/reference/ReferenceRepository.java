package io.driftwatch.backend.reference;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReferenceRepository extends JpaRepository<Reference, UUID> {

  /** The current reference: newest registration (ties broken by the time-ordered id). */
  Optional<Reference> findFirstByModelIdOrderByCreatedAtDescIdDesc(UUID modelId);
}
