-- A person can be put on hold and taken off it again: while held, their OPEN claims count in no
-- live total (their SETTLED ones still do).
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
