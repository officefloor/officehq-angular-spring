-- Each policy version keeps the value its change set the setting to (null: none). Versions already
-- made take the value still recorded for their setting from their from-date.
ALTER TABLE sheet_policy_version ADD COLUMN setting_value VARCHAR(32);
UPDATE sheet_policy_version v SET setting_value = (
    SELECT s.setting_value FROM sheet_setting s
    WHERE s.sheet_id = v.sheet_id AND s.setting_key = v.setting_key AND s.from_date = v.from_date);
