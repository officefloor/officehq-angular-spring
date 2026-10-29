-- The sheet's pending SLA: a PENDING claim whose pending day (the sheet's today when it last went to PENDING) plus
-- this many days is before the sheet's today is late; null (blank) for none. Not dated.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER CHECK (pending_sla >= 0);
