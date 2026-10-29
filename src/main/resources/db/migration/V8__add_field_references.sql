-- Lets a field point at another microdata flow instead of (or in addition
-- to replacing) a fixed codelist: its value is expected to be the entity_id
-- of a current record in that flow. This is how multi-entity models (e.g.
-- the EKE's Gegenpartei/Rahmen/Instrument/Sicherheit graph) are expressed
-- generically - each entity type is its own microdata_flow, and reference
-- fields link between them, including junction-style flows that just hold
-- two reference fields. No new tables per entity type.
ALTER TABLE microdata_field_definition
    ADD COLUMN referenced_flow_id BIGINT REFERENCES microdata_flow (id);
