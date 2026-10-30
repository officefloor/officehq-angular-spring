-- The sheet's today when a claim went to REVIEW; null for a claim that never did. With the sheet's review SLA
-- (not dated) it decides whether a REVIEW claim is late.
ALTER TABLE claim ADD COLUMN review_on DATE;
