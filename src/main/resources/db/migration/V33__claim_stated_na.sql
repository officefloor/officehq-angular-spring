-- Whether the last statement bound a claim to n/a: it had no due then because the rate that would
-- apply to it had ended. Unlike a claim bound with no due at all, it does not stay live.
ALTER TABLE claim ADD COLUMN stated_na BOOLEAN DEFAULT FALSE NOT NULL;
