-- A claim's terms now count business days. Each claim keeps the terms it was fixed with and the date
-- its due-by counts from (its date when it was added); its due-by is the terms-th business day after
-- that date, skipping weekends and the sheet's holidays. The claims already kept count from their date,
-- with the days their due-by was fixed after it; V56 works their due-by out again in business days.
ALTER TABLE claim ADD COLUMN due_from DATE;
ALTER TABLE claim ADD COLUMN terms INTEGER;
UPDATE claim SET due_from = claim_date, terms = GREATEST(0, DATEDIFF(DAY, claim_date, due_by));
ALTER TABLE claim ALTER COLUMN due_from SET NOT NULL;
ALTER TABLE claim ALTER COLUMN terms SET NOT NULL;
