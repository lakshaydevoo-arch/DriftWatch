-- V4: monitoring runs (roadmap task 2.7).
--
-- A run compares one CURRENT dataset with the reference that was current when
-- the run was created. Lifecycle:
--
--   PENDING -> RUNNING -> SUCCEEDED
--                     \-> FAILED
--   PENDING ----------> FAILED     (e.g. interrupted by a backend restart)
--
-- The engine's full drift response is stored as returned (like the reference
-- profile in V3): the backend does not re-model the engine's per-feature
-- results. The checks below make every terminal state complete: a SUCCEEDED
-- run always has a result, a FAILED run always has a reason, so a run can
-- never look finished while holding a partial result.

-- Lets monitoring_run check that its current dataset is a CURRENT dataset of
-- the same model (same technique as reference_profile in V3).
ALTER TABLE reference_profile
    ADD CONSTRAINT uq_reference_profile_id_model UNIQUE (id, model_id);

CREATE TABLE monitoring_run (
    id                   UUID         PRIMARY KEY,
    model_id             UUID         NOT NULL REFERENCES monitored_model (id) ON DELETE RESTRICT,
    reference_profile_id UUID         NOT NULL,
    current_dataset_id   UUID         NOT NULL,
    -- Always 'CURRENT'; exists only to take part in the foreign key.
    current_dataset_role VARCHAR(16)  NOT NULL DEFAULT 'CURRENT',
    status               VARCHAR(16)  NOT NULL,
    -- The DriftConfig the client asked for (NULL = engine defaults). The
    -- complete config the engine applied is part of the result.
    requested_config     JSONB,
    -- Filled when the run succeeds.
    engine_version       VARCHAR(64),
    result               JSONB,
    -- Filled when the run fails. error_kind is a problem kind such as
    -- 'schema-mismatch' or 'analytics-unavailable'; error_details holds the
    -- engine's structured errors, if any.
    error_kind           VARCHAR(64),
    error_message        VARCHAR(2000),
    error_details        JSONB,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,

    -- Each uploaded batch belongs to exactly one run.
    CONSTRAINT uq_monitoring_run_current_dataset UNIQUE (current_dataset_id),
    CONSTRAINT ck_monitoring_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_monitoring_run_role CHECK (current_dataset_role = 'CURRENT'),
    CONSTRAINT ck_monitoring_run_config_object
        CHECK (requested_config IS NULL OR jsonb_typeof(requested_config) = 'object'),
    CONSTRAINT ck_monitoring_run_result_object
        CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
    -- Each status has exactly the fields it should.
    CONSTRAINT ck_monitoring_run_state CHECK (
        (status = 'PENDING'
            AND started_at IS NULL AND finished_at IS NULL
            AND result IS NULL AND error_kind IS NULL)
        OR (status = 'RUNNING'
            AND started_at IS NOT NULL AND finished_at IS NULL
            AND result IS NULL AND error_kind IS NULL)
        OR (status = 'SUCCEEDED'
            AND started_at IS NOT NULL AND finished_at IS NOT NULL
            AND result IS NOT NULL AND engine_version IS NOT NULL
            AND error_kind IS NULL)
        OR (status = 'FAILED'
            AND finished_at IS NOT NULL
            AND result IS NULL AND error_kind IS NOT NULL AND error_message IS NOT NULL)
    ),
    CONSTRAINT ck_monitoring_run_times CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
    ),
    CONSTRAINT fk_monitoring_run_reference
        FOREIGN KEY (reference_profile_id, model_id)
        REFERENCES reference_profile (id, model_id) ON DELETE RESTRICT,
    CONSTRAINT fk_monitoring_run_dataset
        FOREIGN KEY (current_dataset_id, model_id, current_dataset_role)
        REFERENCES dataset (id, model_id, role) ON DELETE RESTRICT
);

-- Run history of a model, newest first.
CREATE INDEX ix_monitoring_run_model_created
    ON monitoring_run (model_id, created_at DESC, id DESC);

-- Startup recovery finds unfinished runs.
CREATE INDEX ix_monitoring_run_unfinished
    ON monitoring_run (status) WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON TABLE monitoring_run IS 'One drift comparison of a CURRENT dataset with a reference profile';
