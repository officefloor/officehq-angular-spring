-- A client's grace period is now optional: null gives the client the grace period of their region
-- (the region_grace_days_<region> setting), or the standard week when the region sets none. Until
-- now every client without a grace period of their own was stored with the standard week.
ALTER TABLE client ALTER COLUMN grace_days DROP DEFAULT;
ALTER TABLE client ALTER COLUMN grace_days SET NULL;
UPDATE client SET grace_days = NULL WHERE grace_days = 7;
