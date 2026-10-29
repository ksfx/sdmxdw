-- Same reason as category.parent_category_id (see V3): on each CategoryScheme
-- aggregate save, Spring Data JDBC deletes the whole category set of the
-- scheme and re-inserts it in one batch. If any category in that scheme is
-- already referenced from categorisation/microdata_categorisation, the
-- transient DELETE violates a non-deferrable FK even though the same row
-- comes right back via the re-INSERT in the same transaction. Deferring the
-- check to COMMIT fixes it, matching the existing parent_category_id FK.
ALTER TABLE categorisation
    DROP CONSTRAINT categorisation_category_id_fkey,
    ADD CONSTRAINT categorisation_category_id_fkey
        FOREIGN KEY (category_id) REFERENCES category (id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE microdata_categorisation
    DROP CONSTRAINT microdata_categorisation_category_id_fkey,
    ADD CONSTRAINT microdata_categorisation_category_id_fkey
        FOREIGN KEY (category_id) REFERENCES category (id) DEFERRABLE INITIALLY DEFERRED;
