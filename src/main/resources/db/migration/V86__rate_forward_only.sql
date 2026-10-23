-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to
-- claims added after it was saved. Claims take their place in the order they are added to a sheet
-- (added_seq, from claim_added_seq; a claim transferred in takes a new place), and a forward-only rate
-- (and each older version of a rate) keeps the place it was saved at (forward_after): it applies to a
-- claim only when the claim's place is after it. A null forward_after applies to every claim.
CREATE SEQUENCE claim_added_seq START WITH 1;
ALTER TABLE claim ADD COLUMN added_seq BIGINT DEFAULT NEXT VALUE FOR claim_added_seq NOT NULL;
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
