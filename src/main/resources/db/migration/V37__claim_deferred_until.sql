-- The day an open claim is deferred until: until the sheet's today reaches it the claim counts in
-- no total, is not overdue, cannot be settled and a statement leaves it unbound. Null when never
-- deferred.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
