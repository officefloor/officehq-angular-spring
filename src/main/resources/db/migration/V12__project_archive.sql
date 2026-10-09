-- Projects are archived rather than deleted: an archived project drops off the project lists but
-- is kept, with its tasks and invoices, and can be brought back.
ALTER TABLE project ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
