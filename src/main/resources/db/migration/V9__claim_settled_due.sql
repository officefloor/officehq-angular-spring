-- What was due on a claim when it was settled, at its person's rate then; later rate changes leave
-- it as it is. Null until the claim is settled, and when its person's grade had no rate then (no
-- figure is fixed, so what is due on it follows the rate).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(12, 2);
