-- The role a user name has on a sheet: clerk or steward. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(16)  NOT NULL CHECK (role IN ('clerk', 'steward'))
);
CREATE UNIQUE INDEX sheet_role_user ON sheet_role (sheet_id, user_name);
-- The sheet's clerk limit: a clerk can settle a claim only when its due is at or below it (a claim
-- whose due is n/a is within any limit). Null means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(14, 2);
