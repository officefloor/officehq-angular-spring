-- The sheet's review SLA in days: a claim in review whose review day (the sheet's today when it went
-- to review) plus them is before the sheet's today is late; null (blank) is none. Not dated.
ALTER TABLE sheet ADD COLUMN review_sla INTEGER;
