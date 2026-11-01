-- Documentation of microdata fields and codes (#23), filled e.g. from the official documents of a
-- reporting framework (SNB EKE: Erläuterungen, Liste Datenfelder, Meldeschema):
--   description: the definition of the field as Markdown
--   attributes:  further source attributes as a JSON object in display order, e.g.
--                {"Feld-ID": "RA-03", "XSD": "RAH/rah_lim_komm", "Einführung": "Paket 1"}
--   code.description: explanation of a code beyond its name
-- Purely descriptive, like the obligation/subtype columns of V11.
ALTER TABLE microdata_field_definition
    ADD COLUMN description TEXT,
    ADD COLUMN attributes TEXT;

ALTER TABLE code ADD COLUMN description TEXT;
