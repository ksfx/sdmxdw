-- Git repository the process folder is linked to (#20 phase 2). One row at most; the access
-- token is encrypted like process secrets. Without a row the process folder is a plain directory.
CREATE TABLE process_git_config (
    id INT PRIMARY KEY CHECK (id = 1),
    repo_url VARCHAR(500) NOT NULL,
    branch VARCHAR(200) NOT NULL DEFAULT 'main',
    sub_folder VARCHAR(300) NOT NULL DEFAULT 'processes',
    username VARCHAR(200),
    token_encrypted TEXT,
    last_pull_at TIMESTAMPTZ,
    last_message TEXT,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- import_job / import_job_run stay until a later release: LegacyImportMigration reads them at
-- startup, which runs after Flyway - dropping them here would lose the jobs of older installations.
