-- The sheet's escalation gap in days (none: off): reminding a claim whose previous reminder was at
-- least that many days before the sheet's today escalates it. Not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER CHECK (escalate_after >= 0);
-- Whether the claim has been escalated; a claim is escalated once ever.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
