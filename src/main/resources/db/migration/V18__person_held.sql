-- Whether a person is on hold: while held, their OPEN claims count in no live total; SETTLED ones still do.
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
