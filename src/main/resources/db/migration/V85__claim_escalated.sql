-- Whether a claim was ever escalated; a claim is escalated at most once.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN DEFAULT FALSE NOT NULL;
