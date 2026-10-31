-- An OPEN claim can be deferred to a day after the sheet's today: until the sheet's today reaches it the claim
-- counts in no total, is not overdue, cannot be settled and is left unbound by a statement.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
