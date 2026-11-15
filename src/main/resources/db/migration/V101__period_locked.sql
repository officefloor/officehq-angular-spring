-- A steward can lock a period: a locked period cannot be reopened until it is unlocked.
ALTER TABLE period ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
