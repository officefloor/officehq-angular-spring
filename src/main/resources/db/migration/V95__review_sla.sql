-- The sheet's review SLA: a REVIEW claim whose review day plus this many days is before the sheet's today is late.
-- Not dated; null means none.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER CHECK (review_sla >= 0);
-- The day (the sheet's today then) the claim last went to REVIEW; null when it never did.
ALTER TABLE claim ADD COLUMN review_on DATE;
