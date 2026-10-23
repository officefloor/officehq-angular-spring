-- Whether an OPEN or PENDING claim is held: a held claim leaves the live totals as a held person's claims do.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
