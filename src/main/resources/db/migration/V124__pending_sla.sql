-- The pending SLA: a PENDING claim whose pending day (the sheet's today when it last went to PENDING)
-- plus this many days is before the sheet's today is late pending. Null (blank) is none.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER;
