-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to
-- claims added after it was saved. forward_after is the id of the newest claim there was when it was
-- saved (claims are numbered in the order they are added); a claim with that id or a lower one keeps
-- the rates that applied to it. Null: the rate applies to every claim. Kept for each version of a
-- rate and in its history, as each version was saved.
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_history ADD COLUMN forward_after BIGINT;
