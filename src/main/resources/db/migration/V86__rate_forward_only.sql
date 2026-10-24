-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to claims
-- added after it was saved. A rate keeps the terms it was saved with, newest first, each applying to the
-- claims added after its mark (the highest claim added when it was saved; none: every claim); null when
-- its newest version applies to every claim. A claim keeps the mark of when it was first added (a clone
-- keeps the original's); null when that is its own id.
ALTER TABLE rate ADD COLUMN terms VARCHAR(4000);
ALTER TABLE claim ADD COLUMN added BIGINT;
