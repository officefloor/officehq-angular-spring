-- Each version of the sheet's policy keeps the value its change set (NULL: blank, none, or a
-- version made before the value was kept).
ALTER TABLE sheet_policy_version ADD COLUMN setting_value VARCHAR(100);
