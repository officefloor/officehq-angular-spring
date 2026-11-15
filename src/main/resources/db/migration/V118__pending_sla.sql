-- The sheet's pending SLA: a PENDING claim whose pending day plus this many days is before the
-- sheet's today is late; none (never late) when null.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER;
-- The sheet's today when the claim last went to PENDING; null when it never did.
ALTER TABLE claim ADD COLUMN pending_on DATE;
