-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to claims added after
-- it was saved. It keeps the highest claim id there was when it was saved (claims_after); a claim with an id up to it
-- keeps the rate that applied to it before, an older version of the same rate included. A rate that applies to every
-- claim has none. An older version keeps the one it had.
ALTER TABLE rate ADD COLUMN claims_after BIGINT;
ALTER TABLE rate_version ADD COLUMN claims_after BIGINT;
