-- Each user's locale: how the screen shows them figures (en: 1234.56, de: 1.234,56). A user without a
-- row is en. Kept per user, across every sheet.
CREATE TABLE user_locale (
    user_name VARCHAR(200) NOT NULL PRIMARY KEY,
    locale    VARCHAR(10)  NOT NULL
);
