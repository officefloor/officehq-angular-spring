-- The sheet's "today": every date-dependent decision and every default date on a sheet uses it,
-- never the real clock. A new sheet starts at the real date of its creation.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CURRENT_DATE WHERE today IS NULL;
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
