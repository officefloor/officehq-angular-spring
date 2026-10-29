-- The sheet's escalation gap: reminding a claim whose previous reminder was at least this many days before the
-- sheet's today escalates it; null (blank) for off. Not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER CHECK (escalate_after >= 0);
-- Whether the claim was ever escalated: a claim is escalated once at most.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
