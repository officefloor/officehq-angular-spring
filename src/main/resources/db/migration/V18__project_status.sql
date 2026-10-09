-- A project is marked active, on hold or finished; existing projects are taken to be active.
ALTER TABLE project ADD COLUMN status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL;

ALTER TABLE project ADD CONSTRAINT project_status_valid CHECK (status IN ('ACTIVE', 'ON_HOLD', 'FINISHED'));
