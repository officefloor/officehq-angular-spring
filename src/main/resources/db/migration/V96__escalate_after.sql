-- The sheet's escalation gap in days: a reminder at least this many days after the claim's previous
-- one escalates it; null (blank) is off. A claim is escalated once ever.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER;
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
