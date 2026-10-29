-- Documentation of a field as specified by the source data model (e.g. the SNB EKE logical
-- data model): obligation as published (M = mandatory, M* = mandatory with further
-- dependencies, O = optional) and, for fields that only exist on a subtype of the entity
-- (XOR specialisations), the subtype name and the classification rule that selects it.
-- Purely descriptive - ingest only enforces the existing "required" flag.
ALTER TABLE microdata_field_definition
    ADD COLUMN obligation VARCHAR(2),
    ADD COLUMN subtype VARCHAR(200),
    ADD COLUMN subtype_rule VARCHAR(200);
