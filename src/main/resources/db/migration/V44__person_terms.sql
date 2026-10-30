-- A person's own terms (days): a claim of theirs is due by its date plus these; null: none (their grade's, or the sheet's).
ALTER TABLE person ADD COLUMN terms INT;
