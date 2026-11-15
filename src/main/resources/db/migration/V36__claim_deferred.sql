-- An OPEN claim may be deferred until a date after the sheet's today: until the sheet's today
-- reaches it the claim counts in no total, is not overdue, cannot be settled and is left unbound
-- by a statement. NULL when never deferred.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
