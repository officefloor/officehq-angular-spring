-- An OPEN or PENDING claim can be reminded, once per sheet's today: the last day a reminder was
-- written for it. Null when it never was.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
