-- The sheet's clerk limit: a clerk settles a claim only when its due is at or below it. NULL means
-- no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(15, 2) CHECK (clerk_limit >= 0);
-- The role each user name holds on the sheet. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward'))
);
CREATE UNIQUE INDEX sheet_role_user ON sheet_role (sheet_id, user_name);
