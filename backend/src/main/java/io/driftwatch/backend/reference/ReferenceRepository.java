package io.driftwatch.backend.reference;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReferenceRepository extends JpaRepository<Reference, UUID> {

  /** The current reference: newest registration (ties broken by the time-ordered id). */
  Optional<Reference> findFirstByModelIdOrderByCreatedAtDescIdDesc(UUID modelId);

  /** The current reference's id only, without loading the (possibly large) profile. */
  <T> Optional<T> findFirstByModelIdOrderByCreatedAtDescIdDesc(UUID modelId, Class<T> type);

  /** Projection of a reference's id. */
  interface IdOnly {
    UUID getId();
  }
}
