-- The people-list columns each user name has switched off on a sheet, comma separated. A name with no
-- row shows every column. Only what the screen shows to that user; not part of the sheet's history and
-- not cloned.
CREATE TABLE user_columns (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)  NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(100)  NOT NULL,
    hidden    VARCHAR(1000) NOT NULL
);
CREATE UNIQUE INDEX user_columns_sheet_user ON user_columns (sheet_id, user_name);
