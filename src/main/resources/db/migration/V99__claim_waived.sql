-- Whether a steward waived the claim's late charge: a waived claim bears no late charge from then on.
ALTER TABLE claim ADD COLUMN waived BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim_history ADD COLUMN waived BOOLEAN DEFAULT FALSE NOT NULL;
