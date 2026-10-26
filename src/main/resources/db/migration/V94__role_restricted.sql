-- A restricted user is a fourth role: it is listed only the people of the crews its person leads or
-- belongs to, and that person. The role check is widened by rebuilding the table, as its check
-- constraint has no name to drop.
CREATE TABLE sheet_role_r (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer', 'restricted')),
    PRIMARY KEY (sheet_id, user_name)
);
INSERT INTO sheet_role_r (sheet_id, user_name, role) SELECT sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_r RENAME TO sheet_role;
