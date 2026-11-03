-- The sheet's pending SLA in days: a pending claim whose pending day (the sheet's today when it last
-- went to pending) plus them is before the sheet's today is late; null (blank) is none. Not dated.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER;
