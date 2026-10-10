-- An owing invoice can be flagged as disputed by the client. It still counts as owed; the flag only marks it.
ALTER TABLE invoice ADD COLUMN disputed BOOLEAN DEFAULT FALSE NOT NULL;
