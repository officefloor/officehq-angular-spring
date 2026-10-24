-- The sheet's review SLA: a REVIEW claim whose review day plus this many days is before the sheet's
-- today is late. Null means none.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER;
