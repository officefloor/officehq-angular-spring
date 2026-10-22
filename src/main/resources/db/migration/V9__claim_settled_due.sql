-- A claim's due fixed when it is settled: its amount at the person's grade's rate at that moment,
-- rounded half up to two decimals; later changes to rates do not alter it. Null while the claim is
-- not settled, and when the grade had no rate at settling (its due then stays live).
ALTER TABLE claim ADD COLUMN settled_due DECIMAL(14, 2);
