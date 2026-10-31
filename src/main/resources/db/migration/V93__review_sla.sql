-- The sheet's review SLA, in days: a REVIEW claim whose review day (the sheet's today when it went to REVIEW) plus
-- these is before the sheet's today is late for review. None means none. Not dated.
ALTER TABLE sheet ADD COLUMN review_sla INT;
