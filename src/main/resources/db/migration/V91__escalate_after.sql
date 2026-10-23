-- The sheet's escalation gap: reminding a claim whose previous reminder was at least this many days
-- before the sheet's today escalates it. Null means off; not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INT;
-- Whether a claim was ever escalated: a claim escalates once at most.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN DEFAULT FALSE NOT NULL;
