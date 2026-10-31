-- A user's locale: how the screen shows them figures. Kept per user across sheets; a user with no line is en.
CREATE TABLE user_locale (
    user_name VARCHAR(100) PRIMARY KEY,
    locale VARCHAR(10) NOT NULL
);
