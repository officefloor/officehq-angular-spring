-- An OPEN claim may be deferred to a date after the sheet's today: until the sheet's today reaches
-- it, the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
-- statement. Null when never deferred; the date is kept once reached.
ALTER TABLE claim ADD COLUMN deferred_until DATE;
