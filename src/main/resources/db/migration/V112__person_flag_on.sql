-- The sheet's today when the person's flag was put on; null for a flag put on before it was kept
-- (or while they were never flagged). A view as of an earlier day leaves the flag out.
ALTER TABLE person ADD COLUMN flag_on DATE;
