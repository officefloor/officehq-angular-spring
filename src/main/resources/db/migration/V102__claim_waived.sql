-- Whether a steward waived the claim's late charge: a waived claim bears none from then on. The
-- sheet's history keeps it too.
ALTER TABLE claim ADD COLUMN waived BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim_revision ADD COLUMN waived BOOLEAN DEFAULT FALSE NOT NULL;
