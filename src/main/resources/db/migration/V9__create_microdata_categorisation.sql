-- Mirrors `categorisation` (dataflow -> category) for microdata flows, so
-- different microdata collections (e.g. several surveys) can be grouped in
-- the same category tree the SDMX explorer already uses, instead of one
-- flat list of all flows.
CREATE TABLE microdata_categorisation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    microdata_flow_id BIGINT NOT NULL REFERENCES microdata_flow (id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES category (id)
);
