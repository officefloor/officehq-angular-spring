-- A claim's due-by is fixed when it is added, from the terms of its person's grade in force on its
-- date (a grade's own terms, setting key terms-<grade>, else the sheet's terms); it does not change
-- when the terms or the claim's date change later. Each claim already stored keeps the due-by it
-- showed: its date plus the sheet's terms (14 days when the sheet set none).
ALTER TABLE claim ADD COLUMN due_by DATE;
UPDATE claim c SET due_by = DATEADD(DAY,
    COALESCE((SELECT CAST(s.setting_value AS INT) FROM sheet_setting s
              WHERE s.sheet_id = c.sheet_id AND s.setting_key = 'terms'), 14),
    c.claim_date);
ALTER TABLE claim ALTER COLUMN due_by SET NOT NULL;
