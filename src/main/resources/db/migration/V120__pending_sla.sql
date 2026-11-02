-- The sheet's pending SLA in days (not dated): a PENDING claim whose pending day plus it is before the sheet's
-- today is late pending. Null means none. A claim keeps the sheet's today it last went to PENDING on.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER;
ALTER TABLE claim ADD COLUMN pending_on DATE;
