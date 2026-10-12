-- The client's tier: STANDARD, KEY or NEW. A tier with its own approval limit (the
-- 'approval_limit_<tier>' setting, in US dollars) uses it instead of the workspace's.
ALTER TABLE client ADD COLUMN tier VARCHAR(16) DEFAULT 'STANDARD' NOT NULL;
