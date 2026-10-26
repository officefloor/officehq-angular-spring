-- The last sheet's today a reminder was written for an OPEN or PENDING claim; null for a claim never reminded.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
