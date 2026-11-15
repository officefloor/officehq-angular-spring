-- An OPEN or PENDING claim can be reminded once per sheet-today: the last day a reminder was
-- written for it. NULL when never reminded.
ALTER TABLE claim ADD COLUMN reminded DATE;
