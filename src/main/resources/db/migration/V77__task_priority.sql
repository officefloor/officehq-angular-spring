-- A task may carry a priority: Low, Medium or High.
ALTER TABLE task ADD COLUMN priority VARCHAR(10);
ALTER TABLE task ADD CONSTRAINT task_priority_valid CHECK (priority IS NULL OR priority IN ('Low', 'Medium', 'High'));
