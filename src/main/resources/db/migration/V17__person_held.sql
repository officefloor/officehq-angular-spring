-- Whether a person is on hold: while they are, their open claims count in no live total; their
-- settled claims still do.
ALTER TABLE person ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
