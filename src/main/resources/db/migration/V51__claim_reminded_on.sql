-- The last day (the sheet's today) a reminder was written for the claim; null when never reminded.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
