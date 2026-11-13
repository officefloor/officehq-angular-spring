-- A sheet's "today": the date every date-dependent decision and default on the sheet uses. It
-- starts as the real date when the sheet is first loaded and is changed on the screen.
ALTER TABLE sheet ADD COLUMN today DATE;
