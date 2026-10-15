-- A second raw axis: whole-number delta and epsilon on each person, absent until given.
ALTER TABLE person ADD COLUMN delta INTEGER;
ALTER TABLE person ADD COLUMN epsilon INTEGER;
