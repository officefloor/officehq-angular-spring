-- The user whose open editor holds the claim: no one else may edit it until that editor is saved or cancelled, or
-- the sheet's today changes. Null while no editor holds it.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
