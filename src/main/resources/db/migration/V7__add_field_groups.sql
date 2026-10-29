-- Generic, survey-agnostic repeating-group mechanism: a field with a
-- group_code belongs to a group of fields that repeats together on a
-- record (e.g. a loan's collaterals, each with its own type/value/currency)
-- instead of representing a single scalar value. Group membership is
-- mutually exclusive with facet_slot at the application layer (only
-- MicrodataFlowService.addField assigns a facet slot, only addGroupField
-- assigns a group code) rather than via a DB constraint, since the two
-- methods' signatures already make the combination unreachable.
ALTER TABLE microdata_field_definition ADD COLUMN group_code VARCHAR(128);
