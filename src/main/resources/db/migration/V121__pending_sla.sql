-- The pending SLA in days: a PENDING claim whose pending day (the sheet's today when it last went to
-- PENDING) plus them is before the sheet's today is late. Null means none; not dated.
ALTER TABLE sheet ADD COLUMN pending_sla INT;
