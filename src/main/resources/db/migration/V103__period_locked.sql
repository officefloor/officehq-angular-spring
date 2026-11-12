-- Whether a steward has locked the period: a locked period cannot be reopened until it is unlocked.
ALTER TABLE period ADD COLUMN locked BOOLEAN DEFAULT FALSE NOT NULL;
