-- Optional reason for a manual correction of an observation (e.g. "revised by reporting bank").
-- Travels with the value on promotion to Beta and shows up in the vintage history.
ALTER TABLE observation ADD COLUMN comment TEXT;
