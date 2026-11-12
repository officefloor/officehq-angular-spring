-- The sheet's "today": every date decision and default date in the sheet uses it, never the real
-- clock. A sheet starts on the real date it was first loaded.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CAST(created_at AS DATE);
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
