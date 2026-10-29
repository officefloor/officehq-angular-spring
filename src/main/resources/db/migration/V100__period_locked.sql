-- Whether a period is locked: while locked, a steward must unlock it before it can be reopened.
ALTER TABLE period ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
