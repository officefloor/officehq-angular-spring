-- Dated settings: a setting that carries a figure (the floor, the late percent, the terms, each
-- grade's terms, the clerk limit and the cap) holds a value per from-date. The value in force on a
-- date is the one with the latest from-date on or before it; before every from-date it is the
-- sheet's own column (its starting value). A sheet holds at most one value per key and from-date.
-- A null value means none (no limit, no cap, or the sheet's terms for a grade).
CREATE TABLE sheet_setting (
    sheet_id      VARCHAR(64) NOT NULL REFERENCES sheet (id),
    setting_key   VARCHAR(32) NOT NULL,
    from_date     DATE        NOT NULL,
    setting_value VARCHAR(32)
);
CREATE UNIQUE INDEX sheet_setting_key_from ON sheet_setting (sheet_id, setting_key, from_date);
