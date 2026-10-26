-- Whether a person is on hold. While held, their open claims count in no live total; their settled ones still do.
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
