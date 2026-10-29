-- Each user's own preferences, kept across sheets: the locale the screen shows them figures in (en unless chosen).
CREATE TABLE user_pref (
    user_name VARCHAR(100) PRIMARY KEY,
    locale VARCHAR(10) DEFAULT 'en' NOT NULL
);
