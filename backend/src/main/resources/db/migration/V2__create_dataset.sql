-- V2: uploaded dataset files (roadmap task 2.4).
--
-- The file itself lives in the content-addressed file store; this row is the
-- record of one upload. Uploading the same bytes twice creates two rows that
-- share one stored file (same sha256).
--
-- Columns filled by later steps (row count, inferred schema, status) are added
-- by the migration of the task that fills them (2.6).

CREATE TABLE dataset (
    id                UUID         PRIMARY KEY,
    -- RESTRICT: a model cannot be deleted while datasets refer to it.
    model_id          UUID         NOT NULL REFERENCES monitored_model (id) ON DELETE RESTRICT,
    role              VARCHAR(16)  NOT NULL,
    -- Lower-case hex SHA-256 of the file; also its address in the file store.
    -- The storage path is derived from it, not stored, so the store's root
    -- directory can move without a data migration. VARCHAR, not CHAR(64):
    -- PostgreSQL's CHAR pads with spaces, and the check below already fixes
    -- the length at 64.
    sha256            VARCHAR(64)  NOT NULL,
    byte_size         BIGINT       NOT NULL,
    -- Metadata only; never used to build a file path.
    original_filename VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_dataset_role CHECK (role IN ('REFERENCE', 'CURRENT')),
    CONSTRAINT ck_dataset_sha256_format CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_dataset_byte_size_positive CHECK (byte_size > 0)
);

-- Datasets are looked up per model (reference dataset, run history).
CREATE INDEX ix_dataset_model_id_created_at ON dataset (model_id, created_at);

COMMENT ON TABLE dataset IS 'One uploaded CSV file; content stored by SHA-256 in the file store';
