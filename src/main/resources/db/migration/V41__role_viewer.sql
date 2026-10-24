-- A third role, VIEWER: sees the sheet and may change nothing. The role's check is rebuilt to allow it.
CREATE TABLE sheet_role_new (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('CLERK', 'STEWARD', 'VIEWER'))
);
INSERT INTO sheet_role_new (id, sheet_id, user_name, role) SELECT id, sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_new RENAME TO sheet_role;
ALTER TABLE sheet_role ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM sheet_role);
CREATE UNIQUE INDEX sheet_role_sheet_user ON sheet_role (sheet_id, user_name);
