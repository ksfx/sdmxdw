-- Deleting observations and microdata records (#25). Nothing is removed physically: deleting
-- closes the current Alpha vintage and notes who deleted it and why. A deletion of published data
-- is pending until the next promotion, which closes the Beta vintage as well; "published as of"
-- still shows the value for earlier dates.
--
-- Adding nullable columns without a default only changes the catalog, also on large tables.

ALTER TABLE observation ADD COLUMN deleted_by VARCHAR(255), ADD COLUMN delete_reason TEXT;
ALTER TABLE microdata_record ADD COLUMN deleted_by VARCHAR(255), ADD COLUMN delete_reason TEXT;

-- a pending deletion: record_id is the Beta record the next promotion closes
ALTER TABLE microdata_pending ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;

-- a record written again after a deletion replaces the pending deletion; whether it is pending
-- itself depends on Beta, as for every new vintage
CREATE OR REPLACE FUNCTION microdata_pending_after_insert() RETURNS trigger AS $$
BEGIN
    IF current_setting('sdmxdw.bulk_load', true) = 'on' THEN
        RETURN NULL;
    END IF;
    DELETE FROM microdata_pending p
    USING new_rows n
    WHERE p.deleted AND n.stage = 'ALPHA' AND n.valid_to IS NULL
      AND p.microdata_flow_id = n.microdata_flow_id AND p.reporting_period = n.reporting_period
      AND p.entity_id = n.entity_id;
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
