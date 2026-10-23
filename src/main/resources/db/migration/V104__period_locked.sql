-- Whether a period is locked by a steward: a locked period cannot be reopened until it is unlocked.
ALTER TABLE period ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
