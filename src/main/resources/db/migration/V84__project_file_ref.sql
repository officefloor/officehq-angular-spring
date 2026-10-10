-- A job can note a reference to a file (e.g. a document number) kept elsewhere; unset when none was noted.
ALTER TABLE project ADD COLUMN file_ref VARCHAR(100);
