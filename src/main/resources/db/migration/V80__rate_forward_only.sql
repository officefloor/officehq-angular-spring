-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to claims added
-- after it was saved, those whose id is above forward_after (the highest claim id in the store when it was
-- saved). Null: it applies to every claim.
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
