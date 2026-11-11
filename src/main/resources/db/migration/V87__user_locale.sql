-- Each user's locale (en or de), kept per user name across sheets; a user with no row has en.
CREATE TABLE user_locale (
    user_name VARCHAR(100) PRIMARY KEY,
    locale    VARCHAR(2)   NOT NULL
);
