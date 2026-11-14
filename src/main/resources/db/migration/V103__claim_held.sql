-- Whether the claim itself is held: an OPEN or PENDING held claim is left out of the live totals
-- as a held person's claims are (a claim bound by a statement stays in).
ALTER TABLE claim ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim_history ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
