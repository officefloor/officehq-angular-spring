-- A statement now binds a claim's due as shown, late charge and floor included: stated_due (and each
-- claim_binding line's due) holds that figure, which the claim shows and totals while bound, its late
-- charge no longer following the sheet's today.
COMMENT ON COLUMN claim.stated_due IS 'Due as shown at the last statement, late charge and floor included';
COMMENT ON COLUMN claim_binding.due IS 'Due as shown when bound, late charge and floor included';
