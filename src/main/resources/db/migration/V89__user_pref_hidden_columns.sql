-- The columns of the people list a user has switched off on their screen, kept per user name across
-- every sheet: their names separated by commas. Empty (and a user with no row) shows every column.
ALTER TABLE user_pref ADD COLUMN hidden_columns VARCHAR(1000) DEFAULT '' NOT NULL;
