-- Microdata at scale (#17): the explorer and the read API list the current records of a flow
-- ordered by reporting period and entity id, and need the reporting periods of a flow. Without
-- an index in this order every first page sorted the whole flow (13 s for a million records).
-- INCLUDE (id): a page is found in the index alone, then only its records are read; the
-- reporting periods are read with a loose index scan (one probe per period).
CREATE INDEX microdata_record_current_order_ix
    ON microdata_record (stage, microdata_flow_id, reporting_period, entity_id) INCLUDE (id)
    WHERE valid_to IS NULL;
