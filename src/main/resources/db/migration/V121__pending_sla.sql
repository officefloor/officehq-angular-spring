-- The sheet's pending SLA in days (none: off): a PENDING claim whose pending day plus that many days
-- is before the sheet's today is late pending. Not dated.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER CHECK (pending_sla >= 0);
-- The sheet's today when the claim last went to PENDING; none when it never has.
ALTER TABLE claim ADD COLUMN pending_on DATE;
