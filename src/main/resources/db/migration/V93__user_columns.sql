-- The people-list columns each user has switched off on their screen, comma-separated; blank shows
-- every column. A user who only chose columns gets the default locale.
ALTER TABLE user_pref ADD COLUMN hidden_columns VARCHAR(500) DEFAULT '' NOT NULL;
