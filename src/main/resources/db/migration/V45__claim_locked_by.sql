-- The user whose open editor locks the claim for everyone else; null when no editor holds it. Taken
-- by the edit button, released when the editor is saved or cancelled or the sheet's today changes.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(200);
