-- A person can be put on hold and taken off it again. While held, their OPEN claims count in no
-- live total (their own, the sheet's, a period's); their SETTLED claims still count.
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
