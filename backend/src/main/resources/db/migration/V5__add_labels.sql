-- V5: ground-truth labels joined to monitoring runs (roadmap task 3.2).
--
-- Labels usually arrive after a batch was monitored. They are uploaded per
-- run, joined to the run's batch by record id in the analytics engine, and
-- the join report is stored. Every upload is kept; the newest is current.

-- The column that identifies a record in both the batch and the labels.
ALTER TABLE monitored_model ADD COLUMN record_id_column VARCHAR(255);

-- The three role columns must be different columns (NULLs are not compared).
ALTER TABLE monitored_model
    ADD CONSTRAINT ck_monitored_model_distinct_columns CHECK (
        target_column IS DISTINCT FROM prediction_column OR target_column IS NULL
    ),
    ADD CONSTRAINT ck_monitored_model_distinct_record_id CHECK (
        record_id_column IS NULL
        OR (record_id_column IS DISTINCT FROM target_column
            AND record_id_column IS DISTINCT FROM prediction_column)
    );

-- Uploaded label files are datasets too (content-addressed like the others).
ALTER TABLE dataset DROP CONSTRAINT ck_dataset_role;
ALTER TABLE dataset
    ADD CONSTRAINT ck_dataset_role CHECK (role IN ('REFERENCE', 'CURRENT', 'LABELS'));

-- Lets run_labels check that the run belongs to the same model.
ALTER TABLE monitoring_run ADD CONSTRAINT uq_monitoring_run_id_model UNIQUE (id, model_id);

CREATE TABLE run_labels (
    id                 UUID        PRIMARY KEY,
    run_id             UUID        NOT NULL,
    model_id           UUID        NOT NULL,
    dataset_id         UUID        NOT NULL,
    -- Always 'LABELS'; exists only to take part in the foreign key.
    dataset_role       VARCHAR(16) NOT NULL DEFAULT 'LABELS',
    engine_version     VARCHAR(64) NOT NULL,
    -- The engine's join report, stored as returned.
    join_report        JSONB       NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One upload is one file.
    CONSTRAINT uq_run_labels_dataset UNIQUE (dataset_id),
    CONSTRAINT ck_run_labels_role CHECK (dataset_role = 'LABELS'),
    CONSTRAINT ck_run_labels_report_object CHECK (jsonb_typeof(join_report) = 'object'),
    CONSTRAINT fk_run_labels_run
        FOREIGN KEY (run_id, model_id)
        REFERENCES monitoring_run (id, model_id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_labels_dataset
        FOREIGN KEY (dataset_id, model_id, dataset_role)
        REFERENCES dataset (id, model_id, role) ON DELETE RESTRICT
);

-- "Current labels of a run" = newest row for that run.
CREATE INDEX ix_run_labels_run_created ON run_labels (run_id, created_at DESC, id DESC);

COMMENT ON TABLE run_labels IS 'Ground-truth labels uploaded for a run, with the engine''s join report';
