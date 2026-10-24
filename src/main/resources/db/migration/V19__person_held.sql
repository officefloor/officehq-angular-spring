-- Whether a person is on hold. While held, their OPEN claims count in no live total (their own,
-- the sheet's or a period's); their SETTLED claims still count.
ALTER TABLE person ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
