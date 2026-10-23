-- A user name may now be restricted: it sees on the people list only the people of the crews its
-- person leads or belongs to, and that person; every other figure is still over the whole sheet.
ALTER TABLE sheet_role ADD COLUMN next_role VARCHAR(16) DEFAULT 'clerk' NOT NULL
    CHECK (next_role IN ('clerk', 'steward', 'viewer', 'restricted'));
UPDATE sheet_role SET next_role = role;
ALTER TABLE sheet_role DROP COLUMN role;
ALTER TABLE sheet_role ALTER COLUMN next_role RENAME TO role;
ALTER TABLE sheet_role ALTER COLUMN role DROP DEFAULT;
