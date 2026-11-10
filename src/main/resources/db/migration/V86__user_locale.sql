-- A user's locale: how the screen shows them figures. Kept per user (by sign-in name), across
-- sheets; a user with no row here has the default, en.
CREATE TABLE user_locale (
    user_name VARCHAR(100) NOT NULL PRIMARY KEY,
    locale    VARCHAR(10)  NOT NULL
);
