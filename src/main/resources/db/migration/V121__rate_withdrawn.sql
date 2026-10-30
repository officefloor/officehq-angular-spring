-- A steward can withdraw a rate's newest version: the version before it applies again, and with none the rate is gone.
-- The change log keeps a withdrawal as a change of its own, marked withdrawn, so the rates as of a day leave out the
-- version it withdrew.
ALTER TABLE rate_change ADD COLUMN withdrawn BOOLEAN DEFAULT FALSE NOT NULL;
