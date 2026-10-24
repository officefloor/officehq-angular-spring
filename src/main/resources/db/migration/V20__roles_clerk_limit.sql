-- The role each user name has on a sheet: CLERK or STEWARD. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('CLERK', 'STEWARD'))
);
CREATE UNIQUE INDEX sheet_role_sheet_user ON sheet_role (sheet_id, user_name);

-- The most a clerk may settle a claim for: its due must be at or below it. Null means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(12, 2) CHECK (clerk_limit >= 0);
