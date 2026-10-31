-- A third role, viewer: sees the sheet and can change nothing. The role column's check is rebuilt to allow it.
CREATE TABLE sheet_role_new (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer')),
    UNIQUE (sheet_id, user_name)
);
INSERT INTO sheet_role_new (id, sheet_id, user_name, role) SELECT id, sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_new RENAME TO sheet_role;
