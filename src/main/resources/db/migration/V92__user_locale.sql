-- Each user's chosen locale, kept per user across sheets; a user with no row shows figures as en.
CREATE TABLE user_pref (
    user_name VARCHAR(100) PRIMARY KEY,
    locale VARCHAR(10) NOT NULL
);
