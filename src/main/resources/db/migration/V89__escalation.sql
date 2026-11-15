-- The sheet's escalation gap: reminding a claim whose previous reminder was at least this many
-- days before the sheet's today escalates it; null when blank (off). Not dated.
ALTER TABLE sheet ADD COLUMN escalate_after INTEGER;
-- Whether the claim has been escalated: a claim is escalated once ever.
ALTER TABLE claim ADD COLUMN escalated BOOLEAN NOT NULL DEFAULT FALSE;
