-- A job can be marked billable (its work is charged to the client) or non-billable (such as internal
-- work). Existing jobs are billable.
ALTER TABLE project ADD COLUMN billable BOOLEAN DEFAULT TRUE NOT NULL;
