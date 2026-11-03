-- A sheet's "today": the date every date-dependent decision and default date on the sheet uses.
-- It starts as the real date when the sheet is first loaded and can be changed on the screen.
ALTER TABLE sheet ADD COLUMN today DATE DEFAULT CURRENT_DATE NOT NULL;
