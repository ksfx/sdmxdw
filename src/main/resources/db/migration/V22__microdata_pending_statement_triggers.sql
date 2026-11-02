-- microdata_pending (V19) maintained once per statement instead of once per row (#17): a bulk
-- load of 5 million records ran the row trigger 5 million times. The statement triggers see all
-- new or changed rows of a statement in transition tables and update microdata_pending
-- set-based - for every way records are written, a single correction as well as a bulk load.
DROP TRIGGER microdata_pending_insert ON microdata_record;
DROP TRIGGER microdata_pending_close ON microdata_record;
DROP FUNCTION microdata_pending_on_insert();
DROP FUNCTION microdata_pending_on_close();

-- new current Alpha vintages are pending unless Beta holds the same payload
CREATE FUNCTION microdata_pending_after_insert() RETURNS trigger AS $$
BEGIN
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

CREATE TRIGGER microdata_pending_insert AFTER INSERT ON microdata_record
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION microdata_pending_after_insert();

-- closed vintages are no longer pending (their successors decide for themselves)
CREATE FUNCTION microdata_pending_after_update() RETURNS trigger AS $$
BEGIN
    DELETE FROM microdata_pending p
    USING new_rows n
    WHERE p.record_id = n.id AND n.valid_to IS NOT NULL;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER microdata_pending_update AFTER UPDATE ON microdata_record
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT EXECUTE FUNCTION microdata_pending_after_update();
