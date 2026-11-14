-- Settling a claim fixes its due: the amount times its person's grade rate at that moment,
-- divided by 100, rounded half up to two decimals. Later rate or grade changes do not alter it.
-- A claim with no figure fixed stays live: one not SETTLED, or settled when its grade had no rate (n/a).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(15, 2);
-- Claims settled before this change take the figure they show now.
UPDATE claim c SET settled_due = (
    SELECT ROUND(c.amount * r.pct / 100, 2)
    FROM person p JOIN rate r ON r.sheet_id = p.sheet_id AND r.grade = p.grade
    WHERE p.id = c.person_id)
WHERE c.status = 'SETTLED';
