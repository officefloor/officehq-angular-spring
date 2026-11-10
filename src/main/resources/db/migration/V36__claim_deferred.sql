-- An OPEN claim can be deferred to a date after the sheet's today: until the sheet's today reaches
-- it the claim counts in no total, is not overdue and cannot be settled.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
