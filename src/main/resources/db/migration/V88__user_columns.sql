-- The people-list columns a user has switched off on their screen, comma-separated. Kept per user
-- across sheets, like their locale; a user with no line sees every column.
CREATE TABLE user_columns (
    user_name VARCHAR(100) PRIMARY KEY,
    hidden VARCHAR(500) NOT NULL
);
