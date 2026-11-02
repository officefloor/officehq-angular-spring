-- A third role, viewer: sees every screen and figure and may change nothing. The role check is
-- rebuilt to allow it (the column check carries a generated name, so the table is remade).
CREATE TABLE sheet_role_new (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward', 'viewer')),
    CONSTRAINT sheet_role_user_unique_v UNIQUE (sheet_id, user_name)
);
INSERT INTO sheet_role_new (id, sheet_id, user_name, role) SELECT id, sheet_id, user_name, role FROM sheet_role;
DROP TABLE sheet_role;
ALTER TABLE sheet_role_new RENAME TO sheet_role;
ALTER TABLE sheet_role ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM sheet_role);
