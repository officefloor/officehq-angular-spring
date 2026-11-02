-- A steward may lock a period so it cannot be reopened until unlocked.
ALTER TABLE period ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
