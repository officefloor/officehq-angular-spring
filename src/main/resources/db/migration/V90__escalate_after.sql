-- The sheet's escalation gap: reminding a claim whose previous reminder was at least this many days
-- before the sheet's today escalates it. Null means off.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER;
-- Whether the claim has ever been escalated; a claim is escalated once at most.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
