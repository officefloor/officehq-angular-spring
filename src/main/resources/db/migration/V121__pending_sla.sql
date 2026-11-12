-- The sheet's pending SLA: a PENDING claim whose pending day plus this many days is before the sheet's today is late.
-- Not dated; null means none.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER CHECK (pending_sla >= 0);
-- The day (the sheet's today then) the claim last went to PENDING; null when it never did.
ALTER TABLE claim ADD COLUMN pending_on DATE;
