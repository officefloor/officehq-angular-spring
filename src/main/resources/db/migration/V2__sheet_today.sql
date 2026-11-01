-- The sheet's "today": every date decision and default date on the sheet uses it, never the clock.
-- It starts as the real date when the sheet is started.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CURRENT_DATE WHERE today IS NULL;
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
