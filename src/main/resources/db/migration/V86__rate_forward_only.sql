-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to claims added after
-- it was saved. forward_after is the highest claim id there was when it was saved (null: it applies to every claim);
-- a claim sees it when the claim's stamp is above it. A version kept of such a rate, and its log line, carry the same.
ALTER TABLE rate ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
ALTER TABLE rate_log ADD COLUMN forward_after BIGINT;
-- The stamp a claim is matched against a forward-only rate with: null for its own id; a clone's copy keeps the
-- original's, so it sees the same rates.
ALTER TABLE claim ADD COLUMN rate_stamp BIGINT;
