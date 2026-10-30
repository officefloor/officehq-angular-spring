-- Whether the claim is held: an OPEN or PENDING held claim is left out of the live totals as a held person's are.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
