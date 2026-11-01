-- The sheet's escalation gap (not dated): reminding a claim whose previous reminder was at least that many
-- days before the sheet's today escalates it, once per claim ever. Null means off.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER;
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
