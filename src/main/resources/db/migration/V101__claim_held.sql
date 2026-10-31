-- Whether the claim is held: while held, an OPEN or PENDING claim no statement binds counts in no live total.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
