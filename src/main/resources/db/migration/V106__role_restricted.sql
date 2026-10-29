-- A fourth role, restricted: sees on the people list only their own person and the people of the crews it leads or
-- belongs to. The role check is rebuilt to allow it, by moving the roles to a table whose check lists all four.
CREATE TABLE sheet_role_r (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer', 'restricted'))
);
INSERT INTO sheet_role_r (id, sheet_id, user_name, role) SELECT id, sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_r RENAME TO sheet_role;
ALTER TABLE sheet_role ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM sheet_role);
CREATE UNIQUE INDEX sheet_role_user ON sheet_role (sheet_id, user_name);
