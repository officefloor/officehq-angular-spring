-- The client's own annual late-interest rate (0 to 30, a percentage) charged instead of the
-- workspace's; null means the workspace's rate, 0 that the client is never charged late interest.
ALTER TABLE client ADD COLUMN interest_rate DECIMAL(5, 2);
