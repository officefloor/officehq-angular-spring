-- A settled claim's due, fixed when it is settled: its amount times its person's grade's rate then,
-- rounded half up to cents. Later changes to rates (or grades) do not alter it. Open and other
-- claims have none (their due stays live), as does one settled while its grade had no rate.
ALTER TABLE claim ADD COLUMN due DECIMAL(15, 2);
UPDATE claim c SET due = (
    SELECT ROUND(c.amount * r.pct / 100, 2) FROM person p JOIN rate r
        ON r.sheet_id = p.sheet_id AND r.grade = p.grade
    WHERE p.id = c.person_id)
WHERE c.status = 'SETTLED';
