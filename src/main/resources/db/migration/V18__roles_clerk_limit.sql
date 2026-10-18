-- The sheet's clerk limit: a clerk may settle a claim only when its due is at or below it (two
-- decimals). None: no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(15, 2);
-- The role the sheet gives a user name. A name with none is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('CLERK', 'STEWARD'))
);
CREATE UNIQUE INDEX sheet_role_sheet_user ON sheet_role (sheet_id, user_name);
