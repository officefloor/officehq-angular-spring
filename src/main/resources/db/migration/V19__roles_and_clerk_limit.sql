-- A sheet's clerk limit: the highest due a clerk may settle; none means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(12, 2) CHECK (clerk_limit >= 0);

-- The role a user name has on a sheet; a name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward')),
    UNIQUE (sheet_id, user_name)
);
