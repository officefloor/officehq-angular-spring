-- A steward can withdraw a rate's newest version: the version before it applies again (or, with
-- none, the rate is gone). An older version keeps whether it was forward-only so it can be put back,
-- and the rate log records each withdrawal so the rates can still be rebuilt as of a day.
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_log ADD COLUMN withdrawn BOOLEAN DEFAULT FALSE NOT NULL;
