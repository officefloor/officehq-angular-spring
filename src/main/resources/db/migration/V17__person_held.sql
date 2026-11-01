-- A person can be put on hold and taken off it again. While held, their OPEN claims count in no
-- live total; their SETTLED claims still count.
ALTER TABLE person ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
