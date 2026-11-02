-- The last day (the sheet's today then) a reminder was written for the claim; null when never.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
