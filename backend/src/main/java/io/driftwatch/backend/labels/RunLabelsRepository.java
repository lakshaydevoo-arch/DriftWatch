package io.driftwatch.backend.labels;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RunLabelsRepository extends JpaRepository<RunLabels, UUID> {

  /** The run's current labels: newest upload (ties broken by the time-ordered id). */
  Optional<RunLabels> findFirstByRunIdOrderByCreatedAtDescIdDesc(UUID runId);
}
