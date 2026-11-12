-- Each policy version keeps the value its setting was given (null: none, such as no limit).
ALTER TABLE policy_version ADD COLUMN setting_value VARCHAR(100);

-- The versions already kept take the value recorded for their setting from their from-date.
UPDATE policy_version v SET setting_value = (
    SELECT d.setting_value FROM dated_setting d
    WHERE d.sheet_id = v.sheet_id AND d.setting_key = v.setting_key
      AND d.from_date = COALESCE(v.from_date, DATE '0001-01-01'));
