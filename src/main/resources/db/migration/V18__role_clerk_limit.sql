-- A user's role on a sheet: clerk or steward. A user name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward'))
);
CREATE UNIQUE INDEX sheet_role_user ON sheet_role (sheet_id, user_name);

-- The highest due a clerk may settle, two decimals; NULL means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(12, 2) CHECK (clerk_limit >= 0);
