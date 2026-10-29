-- The people-list columns each user has switched off on their screen, comma-separated (none unless chosen).
ALTER TABLE user_pref ADD COLUMN hidden_columns VARCHAR(500) DEFAULT '' NOT NULL;
