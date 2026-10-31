-- The last sheet's today on which a reminder was written for the claim; none if it was never reminded.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
