-- V3: reference profiles (roadmap task 2.6).
--
-- A reference profile is the analytics engine's summary of a model's
-- reference dataset. A model can be re-registered with new reference data;
-- every registration is kept, and the newest is the current reference.
--
-- Rows exist only for successfully profiled datasets: the workflow inserts
-- the dataset and its profile in one transaction after the engine succeeds,
-- so no status column is needed.

-- Lets reference_profile check, in the database, that its dataset belongs to
-- the same model and is a REFERENCE dataset (composite foreign key below).
ALTER TABLE dataset
    ADD CONSTRAINT uq_dataset_id_model_role UNIQUE (id, model_id, role);

CREATE TABLE reference_profile (
    id             UUID        PRIMARY KEY,
    model_id       UUID        NOT NULL REFERENCES monitored_model (id) ON DELETE RESTRICT,
    dataset_id     UUID        NOT NULL,
    -- Always 'REFERENCE'; exists only to take part in the foreign key.
    dataset_role   VARCHAR(16) NOT NULL DEFAULT 'REFERENCE',
    engine_version VARCHAR(64) NOT NULL,
    row_count      BIGINT      NOT NULL,
    -- The engine's profile, stored exactly as returned; drift runs send it back.
    profile        JSONB       NOT NULL,
    -- Validation issues the engine reported while profiling.
    issues         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_reference_profile_dataset UNIQUE (dataset_id),
    CONSTRAINT ck_reference_profile_role CHECK (dataset_role = 'REFERENCE'),
    CONSTRAINT ck_reference_profile_row_count CHECK (row_count > 0),
    CONSTRAINT ck_reference_profile_profile_object CHECK (jsonb_typeof(profile) = 'object'),
    CONSTRAINT ck_reference_profile_issues_array CHECK (jsonb_typeof(issues) = 'array'),
    CONSTRAINT fk_reference_profile_dataset
        FOREIGN KEY (dataset_id, model_id, dataset_role)
        REFERENCES dataset (id, model_id, role) ON DELETE RESTRICT
);

-- "Current reference of a model" = newest row for that model.
CREATE INDEX ix_reference_profile_model_created
    ON reference_profile (model_id, created_at DESC, id DESC);

COMMENT ON TABLE reference_profile IS 'Analytics engine profile of a model''s reference dataset';
