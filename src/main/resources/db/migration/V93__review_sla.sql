-- The sheet's review SLA in days (not dated): a REVIEW claim whose review day plus it is before the sheet's
-- today is late for review. Null means none. A claim keeps the sheet's today it went to REVIEW on.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER;
ALTER TABLE claim ADD COLUMN review_on DATE;
