-- A policy version keeps the value its change set the setting to (null: none).
ALTER TABLE sheet_policy_version ADD COLUMN setting_value VARCHAR(20);

-- The versions made until now take the value recorded for their setting from their from-date.
UPDATE sheet_policy_version v
SET setting_value = (SELECT s.setting_value FROM sheet_setting s
                     WHERE s.sheet_id = v.sheet_id AND s.setting_key = v.setting_key
                       AND (s.from_date = v.from_date OR (s.from_date IS NULL AND v.from_date IS NULL)));
