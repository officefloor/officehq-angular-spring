-- A locked period: a steward has locked it, and while it stays locked it cannot be reopened.
ALTER TABLE period ADD COLUMN locked BOOLEAN DEFAULT FALSE NOT NULL;
