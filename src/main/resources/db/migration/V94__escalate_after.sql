-- A sheet's escalation gap, in days, not dated: reminding a claim whose previous reminder was at least
-- that many days before the sheet's today escalates it. Null (blank) is off.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER;
-- Whether a claim was ever escalated: a claim is escalated once only.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
