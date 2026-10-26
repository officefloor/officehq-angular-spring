-- Whether the claim was ever escalated: reminded at least the sheet's escalation gap after its previous
-- reminder. A claim is escalated once ever.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
