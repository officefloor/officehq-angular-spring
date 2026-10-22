-- The sheet's "today": the date every date-dependent decision and default date on the sheet uses,
-- never the real clock. A new sheet starts on the real date; it is changed on the screen.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CAST(created_at AS DATE);
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
