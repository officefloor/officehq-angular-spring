-- A user name may now be a viewer: it sees the whole sheet but can change nothing on it.
ALTER TABLE sheet_role ADD COLUMN next_role VARCHAR(16) DEFAULT 'clerk' NOT NULL
    CHECK (next_role IN ('clerk', 'steward', 'viewer'));
UPDATE sheet_role SET next_role = role;
ALTER TABLE sheet_role DROP COLUMN role;
ALTER TABLE sheet_role ALTER COLUMN next_role RENAME TO role;
ALTER TABLE sheet_role ALTER COLUMN role DROP DEFAULT;
