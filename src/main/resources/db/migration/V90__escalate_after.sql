-- The sheet's escalation gap, in days: reminding a claim whose previous reminder was at least this many days before
-- the sheet's today escalates it. None means off. Not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INT;
-- Whether the claim was ever escalated; a claim is escalated at most once.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
