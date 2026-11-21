package io.driftwatch.backend.model;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MonitoredModelRepository extends JpaRepository<MonitoredModel, UUID> {}
