-- A third role, viewer: sees every figure and can change nothing. The role check is rebuilt to allow it.
CREATE TABLE sheet_role_new (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role VARCHAR(20) NOT NULL,
    CHECK (role IN ('clerk', 'steward', 'viewer'))
);
INSERT INTO sheet_role_new (sheet_id, user_name, role) SELECT sheet_id, user_name, role FROM sheet_role ORDER BY id;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_new RENAME TO sheet_role;
CREATE UNIQUE INDEX sheet_role_sheet_user ON sheet_role (sheet_id, user_name);
