-- A claim's place in the order claims were added to their sheets: the next from claim_added_seq, given
-- by the store when added and again when the claim arrives on another sheet. Claims already kept were
-- all added before any forward-only rate, so they take 0.
CREATE SEQUENCE claim_added_seq START WITH 1;
ALTER TABLE claim ADD COLUMN added BIGINT DEFAULT 0;
UPDATE claim SET added = 0;
ALTER TABLE claim ALTER COLUMN added SET DEFAULT NEXT VALUE FOR claim_added_seq;
ALTER TABLE claim ALTER COLUMN added SET NOT NULL;
-- A rate saved with a from-date on or after the sheet's today then is forward-only: it applies only to
-- claims added after it was saved, those with an added above its forward_after. Null applies to every claim.
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_log ADD COLUMN forward_after BIGINT;
