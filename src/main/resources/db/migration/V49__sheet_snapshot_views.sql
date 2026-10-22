-- What the screen is told about the sheet as it stood at the end of the snapshot's date (its people,
-- claims, rates and periods, and whether it was archived) as JSON, so a user's screen can show the
-- sheet as of a past date. Null on snapshots kept before this column.
ALTER TABLE sheet_snapshot ADD COLUMN views CLOB;
