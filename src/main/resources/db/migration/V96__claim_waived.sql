-- Whether a steward waived a claim's late charge: from then on it bears none.
ALTER TABLE claim ADD COLUMN waived BOOLEAN NOT NULL DEFAULT FALSE;
