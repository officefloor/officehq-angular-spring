-- What each policy version changed: the setting's key and the value it was given, as the audit shows
-- it (two decimals, whole days for the terms, the word for the rounding mode; blank for none).
ALTER TABLE policy_version ADD COLUMN setting_key VARCHAR(40);
ALTER TABLE policy_version ADD COLUMN setting_value VARCHAR(40);

-- The versions already kept take the change they were made from, in the order made.
UPDATE policy_version v SET
    setting_key = (SELECT h.setting_key FROM (
        SELECT sheet_id, setting_key, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id) AS n
        FROM sheet_setting_value_history
        WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit')) h
        WHERE h.sheet_id = v.sheet_id AND h.n = v.version_number),
    setting_value = (SELECT h.shown FROM (
        SELECT sheet_id, ROW_NUMBER() OVER (PARTITION BY sheet_id ORDER BY id) AS n,
            COALESCE(setting_text, CASE WHEN setting_key = 'terms' THEN CAST(CAST(setting_value AS INT) AS VARCHAR)
                ELSE CAST(setting_value AS VARCHAR) END, '') AS shown
        FROM sheet_setting_value_history
        WHERE setting_key IN ('rounding', 'floor', 'cap', 'terms', 'late-pct', 'clerk-limit')) h
        WHERE h.sheet_id = v.sheet_id AND h.n = v.version_number);
