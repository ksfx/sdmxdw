-- Text search in large microdata flows (#17): searching the JSON of a million records took
-- 10 seconds even with the table in memory. In large flows the explorer's text search matches
-- entity ids only, with this trigram index (also for parts of an id, e.g. "500000").
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX microdata_record_entity_trgm_ix
    ON microdata_record USING gin (entity_id gin_trgm_ops) WHERE valid_to IS NULL;
