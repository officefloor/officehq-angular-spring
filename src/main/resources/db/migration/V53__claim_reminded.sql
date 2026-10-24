-- The sheet's today on which a reminder was last written for the claim; null when none ever was.
ALTER TABLE claim ADD COLUMN reminded_on DATE;
