-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to the
-- claims added after it was saved. forward_after is the highest claim id there was when the version was
-- saved (a claim with a greater id was added after it); null when the version applies to every claim.
-- The rate row carries its newest version's, each rate_change row the one of the version it made.
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_change ADD COLUMN forward_after BIGINT;
