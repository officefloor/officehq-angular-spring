-- The user whose open editor locks the claim for everyone else; none when unlocked. Released when the
-- editor is saved or cancelled, or the sheet's today changes.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
