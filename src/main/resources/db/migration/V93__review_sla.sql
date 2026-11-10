-- The sheet's review SLA in days (none: off): a REVIEW claim whose review day plus that many days is
-- before the sheet's today is late for review. Not dated.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER CHECK (review_sla >= 0);
-- The sheet's today when the claim last went to REVIEW; none when it never has.
ALTER TABLE claim ADD COLUMN review_on DATE;
