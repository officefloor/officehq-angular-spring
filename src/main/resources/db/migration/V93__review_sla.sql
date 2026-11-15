-- The sheet's review SLA: a REVIEW claim whose review day plus this many days is before the
-- sheet's today is late; null when blank (none). Not dated.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER;
-- The sheet's today when the claim went to REVIEW; null when it never did.
ALTER TABLE claim ADD COLUMN review_on DATE;
