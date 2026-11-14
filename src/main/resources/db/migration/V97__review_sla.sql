-- The review SLA: a REVIEW claim whose review day (the sheet's today when it went to REVIEW) plus
-- this many days is before the sheet's today is late for review. Null (blank) is none.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER;
