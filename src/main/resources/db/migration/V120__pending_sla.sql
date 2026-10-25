-- The sheet's pending SLA: a PENDING claim whose pending day plus this many days is before the sheet's
-- today is late. Null means none.
ALTER TABLE sheet ADD COLUMN pending_sla INTEGER;
