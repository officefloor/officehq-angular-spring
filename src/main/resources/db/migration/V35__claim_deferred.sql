-- The date an OPEN claim is deferred until: before the sheet's today reaches it the claim counts in no total, is not
-- overdue, cannot be settled and is left unbound by a statement. Null for a claim never deferred.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
