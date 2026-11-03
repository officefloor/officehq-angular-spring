-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to
-- claims added after it was saved. Claims and forward-only rates take their place in one order from
-- claim_added_seq: a claim takes the next when it is added (a moved, copied or split-off claim keeps
-- its own), a forward-only rate (and each of its versions) the next when it is saved, and a claim
-- sees a forward-only rate only when its place is after the rate's. Claims already stored come first.
CREATE SEQUENCE claim_added_seq START WITH 1;
ALTER TABLE claim ADD COLUMN added BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_revision ADD COLUMN forward_after BIGINT;
ALTER TABLE claim_revision ADD COLUMN added BIGINT DEFAULT 0 NOT NULL;
