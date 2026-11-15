-- Each user's locale, by user name (not per sheet): how figures are shown on that user's screen.
-- A user with no row has en.
CREATE TABLE user_locale (
    user_name VARCHAR(100) NOT NULL PRIMARY KEY,
    locale    VARCHAR(10)  NOT NULL CHECK (locale IN ('en', 'de'))
);
