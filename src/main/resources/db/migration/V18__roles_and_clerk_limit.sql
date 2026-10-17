-- The sheet's clerk limit: a clerk can settle a claim only when its due is at or below it. Null
-- means no limit.
ALTER TABLE sheet ADD COLUMN clerk_limit DECIMAL(14, 2);

-- The role given to a user name on a sheet. A name with no row is a clerk.
CREATE TABLE sheet_role (
    id        BIGINT AUTO_INCREMENT NOT NULL PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward')),
    CONSTRAINT sheet_role_user UNIQUE (sheet_id, user_name)
);
