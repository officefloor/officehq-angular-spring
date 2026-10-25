-- The settings that carry a figure (the floor, the late percent, the sheet's and each grade's terms,
-- the clerk limit and the cap) are dated: each value is in force from its from-date until the next
-- from-date of the same key. A blank value clears the setting from its date (no limit, no cap, a
-- grade's own terms falling back to the sheet's). Before every from-date a key has its starting value.
CREATE TABLE sheet_setting_dated (
    sheet_id      VARCHAR(100) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(100) NOT NULL,
    from_date     DATE         NOT NULL,
    setting_value VARCHAR(200) NOT NULL,
    PRIMARY KEY (sheet_id, setting_key, from_date)
);
-- The values already set stay in force on every date.
INSERT INTO sheet_setting_dated (sheet_id, setting_key, from_date, setting_value)
    SELECT sheet_id, setting_key, DATE '0001-01-01', setting_value FROM sheet_setting
    WHERE setting_key IN ('floor', 'late-pct', 'terms', 'terms-A', 'terms-B', 'terms-C', 'clerk-limit', 'cap');
DELETE FROM sheet_setting
    WHERE setting_key IN ('floor', 'late-pct', 'terms', 'terms-A', 'terms-B', 'terms-C', 'clerk-limit', 'cap');
