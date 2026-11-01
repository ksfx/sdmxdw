-- Pending changes of microdata at scale (#17): which current Alpha records differ from their
-- current Beta record is kept in a narrow table, maintained when Alpha vintages are written and
-- closed, instead of comparing the payloads of the whole flow on every explorer page (minutes
-- for a million records).
--   a row = current Alpha vintage whose payload is not the payload of the current Beta vintage
--           (or there is none): what the next promotion publishes
-- Triggers keep it up to date for every way Alpha is written (bulk load, ingest, correction);
-- promotion empties it for the flow. A separate table instead of a column, so that this
-- migration does not rewrite every record of large installations.
CREATE TABLE microdata_pending (
    record_id          BIGINT PRIMARY KEY REFERENCES microdata_record (id) ON DELETE CASCADE,
    microdata_flow_id  BIGINT NOT NULL,
    reporting_period   VARCHAR(20) NOT NULL,
    entity_id          VARCHAR(255) NOT NULL
);
CREATE INDEX microdata_pending_flow_ix
    ON microdata_pending (microdata_flow_id, reporting_period, entity_id);

INSERT INTO microdata_pending (record_id, microdata_flow_id, reporting_period, entity_id)
SELECT a.id, a.microdata_flow_id, a.reporting_period, a.entity_id
FROM microdata_record a
LEFT JOIN microdata_record b
       ON b.microdata_flow_id = a.microdata_flow_id AND b.entity_id = a.entity_id
      AND b.reporting_period = a.reporting_period AND b.stage = 'BETA' AND b.valid_to IS NULL
WHERE a.stage = 'ALPHA' AND a.valid_to IS NULL
  AND b.payload_json IS DISTINCT FROM a.payload_json;

-- a new current Alpha vintage is pending unless Beta holds the same payload
CREATE FUNCTION microdata_pending_on_insert() RETURNS trigger AS $$
BEGIN
    IF NEW.stage = 'ALPHA' AND NEW.valid_to IS NULL AND NOT EXISTS (
            SELECT 1 FROM microdata_record b
            WHERE b.microdata_flow_id = NEW.microdata_flow_id AND b.entity_id = NEW.entity_id
              AND b.reporting_period = NEW.reporting_period AND b.stage = 'BETA'
              AND b.valid_to IS NULL AND b.payload_json = NEW.payload_json) THEN
        INSERT INTO microdata_pending (record_id, microdata_flow_id, reporting_period, entity_id)
        VALUES (NEW.id, NEW.microdata_flow_id, NEW.reporting_period, NEW.entity_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER microdata_pending_insert AFTER INSERT ON microdata_record
    FOR EACH ROW EXECUTE FUNCTION microdata_pending_on_insert();

-- a closed Alpha vintage is no longer pending (its successor decides for itself)
CREATE FUNCTION microdata_pending_on_close() RETURNS trigger AS $$
BEGIN
    DELETE FROM microdata_pending WHERE record_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER microdata_pending_close AFTER UPDATE OF valid_to ON microdata_record
    FOR EACH ROW WHEN (OLD.valid_to IS NULL AND NEW.valid_to IS NOT NULL AND NEW.stage = 'ALPHA')
    EXECUTE FUNCTION microdata_pending_on_close();
