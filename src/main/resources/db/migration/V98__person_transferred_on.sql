-- The sheet's today when the person arrived here by a transfer as themselves; null for anyone else.
ALTER TABLE person ADD COLUMN transferred_on DATE;
