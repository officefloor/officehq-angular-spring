-- Each user's locale: how the screen shows them figures. A user with no row is en.
CREATE TABLE user_locale (
    user_name VARCHAR(100) PRIMARY KEY,
    locale    VARCHAR(10)  NOT NULL,
    CONSTRAINT user_locale_known CHECK (locale IN ('en', 'de'))
);
