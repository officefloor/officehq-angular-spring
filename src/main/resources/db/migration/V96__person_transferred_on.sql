-- The sheet-today, on the sheet they arrived on, a person was last transferred; null when never transferred.
ALTER TABLE person ADD COLUMN transferred_on DATE;
