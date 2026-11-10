-- A steward can lock a period and unlock it again. A locked period cannot be reopened.
ALTER TABLE period ADD COLUMN locked BOOLEAN DEFAULT FALSE NOT NULL;
