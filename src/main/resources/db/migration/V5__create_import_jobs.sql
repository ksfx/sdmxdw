CREATE TABLE import_job (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dataflow_id BIGINT NOT NULL REFERENCES dataflow (id),
    name VARCHAR(255) NOT NULL,
    script_path VARCHAR(500) NOT NULL,
    script_args VARCHAR(1000) NOT NULL DEFAULT '',
    cron_schedule VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_job_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES import_job (id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILURE', 'DRY_RUN')),
    triggered_by VARCHAR(20) NOT NULL CHECK (triggered_by IN ('SCHEDULED', 'MANUAL')),
    output_log TEXT
);

CREATE INDEX import_job_run_job_id_ix ON import_job_run (job_id, started_at DESC);
