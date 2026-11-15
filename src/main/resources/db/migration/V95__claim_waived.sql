-- Whether a steward has waived the claim's late charge: a waived claim bears none from then on.
ALTER TABLE claim ADD COLUMN waived BOOLEAN NOT NULL DEFAULT FALSE;
