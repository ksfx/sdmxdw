-- Reason of a manual correction, carried along on promotion (like observation.comment).
ALTER TABLE microdata_record ADD COLUMN comment TEXT;

-- The audit log now covers microdata flows too: exactly one of the two references is set.
ALTER TABLE audit_log ALTER COLUMN dataflow_id DROP NOT NULL;
ALTER TABLE audit_log ADD COLUMN microdata_flow_id BIGINT REFERENCES microdata_flow (id);
ALTER TABLE audit_log ADD CONSTRAINT audit_log_one_resource
    CHECK ((dataflow_id IS NULL) <> (microdata_flow_id IS NULL));
CREATE INDEX audit_log_microdata_flow_ix ON audit_log (microdata_flow_id, performed_at);
