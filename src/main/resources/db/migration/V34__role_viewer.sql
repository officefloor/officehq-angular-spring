-- A viewer is a third role: it sees the sheet and changes nothing. The role check is widened by
-- rebuilding the table, as its check constraint has no name to drop.
CREATE TABLE sheet_role_v (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer')),
    PRIMARY KEY (sheet_id, user_name)
);
INSERT INTO sheet_role_v (sheet_id, user_name, role) SELECT sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_v RENAME TO sheet_role;
