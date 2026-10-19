-- A claim with a negative amount is a correction: it names, by number, the settled claim of the same person it
-- corrects. None for any other claim.
ALTER TABLE claim ADD COLUMN corrects INTEGER;
