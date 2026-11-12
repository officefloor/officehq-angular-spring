-- The last day (the sheet's today then) a reminder was written for a claim; none when never reminded.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
