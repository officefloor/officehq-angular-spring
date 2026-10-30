-- Whether a person is on hold: while held, their OPEN claims count in no live total; their SETTLED
-- claims still count.
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
