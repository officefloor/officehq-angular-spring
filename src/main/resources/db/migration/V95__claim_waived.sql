-- A steward may waive a claim's late charge: from the sheet's today it was waived on, it bears none.
ALTER TABLE claim ADD COLUMN waived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE claim ADD COLUMN waived_on DATE;
