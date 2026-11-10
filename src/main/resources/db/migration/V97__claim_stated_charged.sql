-- A statement binds a claim's due as shown, late charge included: kept beside the bound figure.
ALTER TABLE claim ADD COLUMN stated_charged DECIMAL(14, 2);
