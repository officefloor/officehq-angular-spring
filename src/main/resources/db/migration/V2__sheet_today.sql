-- The sheet's "today": every date-dependent decision and default date in the sheet uses it, never
-- the real clock. A new sheet starts it at the real date.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CAST(created_at AS DATE);
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
