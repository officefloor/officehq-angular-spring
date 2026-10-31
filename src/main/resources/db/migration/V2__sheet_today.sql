-- A sheet's "today": the date every date-dependent decision and default on the sheet uses, in
-- place of the real clock. It starts as the real date when the sheet is first loaded.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CAST(created_at AS DATE);
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
