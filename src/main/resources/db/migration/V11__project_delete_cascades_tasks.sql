-- A project can be deleted once it is no longer needed. Its tasks belong to it and go with it;
-- invoices are financial records, so the foreign key still blocks deleting a project that has any.
ALTER TABLE task DROP CONSTRAINT task_project_fk;
ALTER TABLE task ADD CONSTRAINT task_project_fk
    FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE;
