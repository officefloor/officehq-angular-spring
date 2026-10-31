-- A statement binds a claim's late charge with its figure: while bound, the claim shows and totals
-- stated_due plus stated_late (the late charge it had then), no longer following the sheet's today.
-- None on a claim bound before this, whose late charge keeps following the sheet's today.
ALTER TABLE claim ADD COLUMN stated_late DECIMAL(14, 2);
