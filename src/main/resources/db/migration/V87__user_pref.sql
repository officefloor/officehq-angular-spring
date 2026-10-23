-- A user's own preferences, kept per user name across every sheet: the locale the screen shows
-- figures in (en or de). A user with no row has en.
CREATE TABLE user_pref (
    user_name VARCHAR(200) PRIMARY KEY,
    locale    VARCHAR(8)   NOT NULL
);
