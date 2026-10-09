-- A client can be flagged a key account, so the office can pick out its most important clients at a
-- glance. Existing clients are not key accounts.
ALTER TABLE client ADD COLUMN key_account BOOLEAN DEFAULT FALSE NOT NULL;
