-- The payment reference the client quoted, if any; null when none was given.
ALTER TABLE payment ADD COLUMN reference VARCHAR(64);
