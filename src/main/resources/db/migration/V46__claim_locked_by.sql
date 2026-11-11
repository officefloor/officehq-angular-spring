-- The user whose open editor locks a claim against other users' edits: taken by the edit button, released when the
-- editor is saved or cancelled, or the sheet's today changes. Null: not locked.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(100);
