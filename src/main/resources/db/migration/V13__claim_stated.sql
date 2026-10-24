-- Whether a claim is bound to the figure it had at its person's last statement, and that figure.
-- A bound claim shows and totals stated_due whatever later rate or grade changes would make it,
-- until the person's next statement rebinds (or, when the claim no longer counts, unbinds) it.
-- stated_due is null when the claim is unbound, and when it had no figure (no rate) when stated.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(12, 2);
