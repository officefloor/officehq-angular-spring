-- A sheet's "today": every date-dependent decision and default date on the sheet uses it,
-- never the real clock. It starts as the real date when the sheet is created.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CURRENT_DATE WHERE today IS NULL;
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
