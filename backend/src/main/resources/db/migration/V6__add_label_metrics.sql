-- V6: performance metrics with each labels upload (roadmap task 3.3).
--
-- The engine now scores the joined rows. Its metrics and the configuration
-- it applied are stored with the join report, exactly as returned. Uploads
-- made before V6 have neither (NULL); every later upload has both.

ALTER TABLE run_labels
    ADD COLUMN config  JSONB,
    ADD COLUMN metrics JSONB,
    ADD CONSTRAINT ck_run_labels_config_object
        CHECK (config IS NULL OR jsonb_typeof(config) = 'object'),
    ADD CONSTRAINT ck_run_labels_metrics_object
        CHECK (metrics IS NULL OR jsonb_typeof(metrics) = 'object'),
    -- Both or neither.
    ADD CONSTRAINT ck_run_labels_metrics_with_config
        CHECK ((config IS NULL) = (metrics IS NULL));
