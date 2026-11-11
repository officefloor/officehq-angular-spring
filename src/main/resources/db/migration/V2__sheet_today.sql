-- Each sheet keeps its own "today": every date-dependent decision and default date uses it, never
-- the real clock. A sheet starts with the real date of the day it was created.
ALTER TABLE sheet ADD COLUMN today DATE;
UPDATE sheet SET today = CURRENT_DATE WHERE today IS NULL;
ALTER TABLE sheet ALTER COLUMN today SET NOT NULL;
