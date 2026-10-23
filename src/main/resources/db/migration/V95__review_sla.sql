-- The review SLA in days: a REVIEW claim whose review day (the sheet's today when it went to REVIEW)
-- plus them is before the sheet's today is late. Null means none; not dated.
ALTER TABLE sheet ADD COLUMN review_sla INT;
