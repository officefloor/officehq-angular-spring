-- The last sheet's today a reminder was written for a claim; null when it was never reminded.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
