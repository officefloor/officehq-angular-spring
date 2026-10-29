-- The sheet's today when a transferred person arrived, and who transferred them; null: not transferred, or before that was kept.
ALTER TABLE person ADD COLUMN arrived_on DATE;
ALTER TABLE person ADD COLUMN arrived_by VARCHAR(100);
