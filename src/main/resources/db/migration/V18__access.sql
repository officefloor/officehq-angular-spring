-- The role a user name has on a sheet: clerk or steward. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(20)  NOT NULL,
    CONSTRAINT sheet_role_known CHECK (role IN ('clerk', 'steward')),
    CONSTRAINT sheet_role_unique UNIQUE (sheet_id, user_name)
);

-- The most due a clerk may settle on a claim; null means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(14, 2);
