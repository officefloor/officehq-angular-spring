-- Each sheet's own "today": every date-dependent decision and default date uses it, never the
-- real clock. It starts as the real date the sheet was first loaded.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CAST(created_at AS DATE);
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
