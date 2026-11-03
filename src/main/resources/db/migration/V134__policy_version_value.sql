-- The value a policy version's setting was given when the version was made, as shown (null: made
-- before it was kept).
ALTER TABLE policy_version ADD COLUMN value_shown VARCHAR(100);
