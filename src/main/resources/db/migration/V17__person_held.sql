-- A person can be put on hold: while held, their open claims count in no live total.
ALTER TABLE person ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
