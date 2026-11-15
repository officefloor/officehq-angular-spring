-- Whether the claim is held: a held open claim, unless bound by a statement, counts in no live total.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
