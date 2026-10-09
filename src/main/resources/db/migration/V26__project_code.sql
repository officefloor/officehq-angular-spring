-- Each job carries a short reference code, given when it is added. Codes are stored upper-cased so
-- no two jobs share one regardless of how it was typed; this is the backstop for the API check. Jobs
-- added before codes existed have none (multiple NULLs are allowed by the unique constraint).
ALTER TABLE project ADD COLUMN code VARCHAR(20);
ALTER TABLE project ADD CONSTRAINT project_code_uq UNIQUE (code);
