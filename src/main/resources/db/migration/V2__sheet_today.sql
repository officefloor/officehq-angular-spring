-- The sheet's today: every date-dependent decision and default date uses it, never the real clock.
-- A new sheet starts at the real date (set by the server when the sheet is first loaded).
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CURRENT_DATE WHERE today IS NULL;
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
