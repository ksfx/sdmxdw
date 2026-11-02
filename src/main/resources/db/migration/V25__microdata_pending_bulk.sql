-- Bulk loads maintain microdata_pending themselves, one flow and period at a time, so the
-- statements touch a single partition (#17); the triggers would compare every new row with the
-- Beta records of all partitions. A load marks its transaction with SET LOCAL sdmxdw.bulk_load.
CREATE OR REPLACE FUNCTION microdata_pending_after_insert() RETURNS trigger AS $$
BEGIN
    IF current_setting('sdmxdw.bulk_load', true) = 'on' THEN
        RETURN NULL;
    END IF;
    INSERT INTO microdata_pending (record_id, microdata_flow_id, reporting_period, entity_id)
    SELECT n.id, n.microdata_flow_id, n.reporting_period, n.entity_id
    FROM new_rows n
    WHERE n.stage = 'ALPHA' AND n.valid_to IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM microdata_record b
          WHERE b.microdata_flow_id = n.microdata_flow_id AND b.entity_id = n.entity_id
            AND b.reporting_period = n.reporting_period AND b.stage = 'BETA'
            AND b.valid_to IS NULL AND b.payload_json = n.payload_json);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION microdata_pending_after_update() RETURNS trigger AS $$
BEGIN
    IF current_setting('sdmxdw.bulk_load', true) = 'on' THEN
        RETURN NULL;
    END IF;
    DELETE FROM microdata_pending p
    USING new_rows n
    WHERE p.record_id = n.id AND n.valid_to IS NOT NULL;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
