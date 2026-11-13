-- The sheet's escalation gap: reminding a claim whose previous reminder was at least this many days before the
-- sheet's today escalates it. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER CHECK (escalate_after >= 0);
-- Whether a claim was escalated: a claim is escalated once ever.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
