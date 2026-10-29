-- Whether a claim is held: while held, an open or pending one counts in no live total unless a statement binds it.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
