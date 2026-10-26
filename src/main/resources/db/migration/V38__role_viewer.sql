-- A third role, viewer: sees everything and changes nothing. The role check is rebuilt to allow it, by moving the
-- roles to a table whose check lists clerk, steward and viewer.
CREATE TABLE sheet_role_v (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer'))
);
INSERT INTO sheet_role_v (id, sheet_id, user_name, role) SELECT id, sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_v RENAME TO sheet_role;
ALTER TABLE sheet_role ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM sheet_role);
CREATE UNIQUE INDEX sheet_role_user ON sheet_role (sheet_id, user_name);
