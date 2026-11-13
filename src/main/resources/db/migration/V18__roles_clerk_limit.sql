-- A sheet's clerk limit (two decimals): a clerk can settle a claim only when its due is at or below
-- it. Null means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(12, 2);

-- The role a sheet's settings assign a user name: clerk or steward. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role VARCHAR(20) NOT NULL,
    CHECK (role IN ('clerk', 'steward'))
);
CREATE UNIQUE INDEX sheet_role_sheet_user ON sheet_role (sheet_id, user_name);
