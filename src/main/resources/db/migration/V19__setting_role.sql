-- The sheet's settings by key: a setting with no row has no value (the clerk limit: no limit).
CREATE TABLE sheet_setting (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(200) NOT NULL,
    PRIMARY KEY (sheet_id, setting_key)
);
-- The role the sheet assigns a user name; a name with no row is a clerk.
CREATE TABLE sheet_role (
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(20)  NOT NULL CHECK (role IN ('clerk', 'steward')),
    PRIMARY KEY (sheet_id, user_name)
);
