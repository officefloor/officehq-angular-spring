-- An OPEN claim can be deferred to a date after the sheet's today: until the sheet's today reaches
-- that date it counts in no total, is not overdue, cannot be settled and no statement binds it.
-- Null when the claim was never deferred.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
