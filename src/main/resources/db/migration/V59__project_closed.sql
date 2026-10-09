-- A job can be closed; once closed no new invoice can be raised on it. Existing jobs are open.
ALTER TABLE project ADD COLUMN closed BOOLEAN NOT NULL DEFAULT FALSE;
