-- Retention held back on an invoice can be released once the job is finished, making it due. The
-- percentage is kept on record; once released it no longer holds anything back.
ALTER TABLE invoice ADD COLUMN retention_released BOOLEAN DEFAULT FALSE NOT NULL;
