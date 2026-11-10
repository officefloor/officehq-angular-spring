-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to
-- claims added after it was saved. Claims and forward-only rates draw from one sequence, so a
-- claim's added_seq above a rate's forward_after means the claim was added after the rate was saved.
CREATE SEQUENCE entry_seq START WITH 1;
ALTER TABLE claim ADD COLUMN added_seq BIGINT DEFAULT NEXT VALUE FOR entry_seq NOT NULL;
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_log ADD COLUMN forward_after BIGINT;
