-- A person may carry tags: trimmed, lower-cased, each once, kept sorted and comma separated; empty for none.
-- Their tags are kept among the sheet's (claim_tag), and a claim counts for a tag its person carries.
ALTER TABLE person ADD COLUMN tags VARCHAR(2000) NOT NULL DEFAULT '';
