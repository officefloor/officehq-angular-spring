-- Each user's locale, by name: how the screen shows them figures (en unless chosen). Not per sheet.
CREATE TABLE user_locale (
    user_name VARCHAR(100) PRIMARY KEY,
    locale    VARCHAR(10)  NOT NULL
);
