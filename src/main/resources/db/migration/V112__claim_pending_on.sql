-- The sheet's today when a claim last went to PENDING; null for a claim that never did. With the sheet's
-- pending SLA (not dated) it decides whether a PENDING claim is late.
ALTER TABLE claim ADD COLUMN pending_on DATE;
