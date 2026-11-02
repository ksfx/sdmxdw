-- The trigram index of V21 made bulk loads almost twice as slow: GIN flushed its 4 MB pending
-- list again and again (500,000 inserts: 52 s, without the index 30 s). With a 64 MB pending
-- list new entries are merged in bulk later (by vacuum or when full): 34 s.
ALTER INDEX microdata_record_entity_trgm_ix SET (gin_pending_list_limit = 65536);
