-- Whether the person is on hold. While held, their OPEN claims count in no live total (their own claimed and
-- due-total, the sheet's totals, a period's); their SETTLED claims still do.
ALTER TABLE person ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
