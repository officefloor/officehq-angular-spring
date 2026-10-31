-- An older version of a rate keeps whether it was forward-only (the claim id it applied past; null: every
-- claim), so withdrawing the newer version brings it back as it was.
ALTER TABLE rate_version ADD COLUMN forward_after BIGINT;
