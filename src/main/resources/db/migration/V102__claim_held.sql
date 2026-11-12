-- Whether the claim is held: an OPEN or PENDING held claim leaves the live totals unless a statement binds it.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
