-- The locale each user name has chosen on a sheet: en or de. A name with no row has en. Only how the
-- screen shows figures to that user; not part of the sheet's history and not cloned.
CREATE TABLE user_locale (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100) NOT NULL,
    locale    VARCHAR(10)  NOT NULL CHECK (locale IN ('en', 'de'))
);
CREATE UNIQUE INDEX user_locale_sheet_user ON user_locale (sheet_id, user_name);
