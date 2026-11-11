-- The people-list columns each user has switched off, kept per user name across sheets; a user with no row sees them all.
CREATE TABLE user_columns (
    user_name VARCHAR(100)  PRIMARY KEY,
    hidden    VARCHAR(1000) NOT NULL
);
