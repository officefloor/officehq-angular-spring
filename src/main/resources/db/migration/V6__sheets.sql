CREATE TABLE sheet (
    id VARCHAR(255) PRIMARY KEY
);
-- A person belongs to the named sheet it was added to; people without one are on the unnamed sheet.
ALTER TABLE person ADD COLUMN sheet_id VARCHAR(255) REFERENCES sheet (id) ON DELETE CASCADE;
