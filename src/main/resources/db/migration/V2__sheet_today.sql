-- Each sheet has its own "today": every date decision and default date uses it, never the real
-- clock. It starts as the real date when the sheet is created.
ALTER TABLE sheet ADD COLUMN today DATE DEFAULT CURRENT_DATE NOT NULL;
