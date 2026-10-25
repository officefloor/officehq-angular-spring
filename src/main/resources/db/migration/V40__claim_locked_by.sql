-- Opening a claim's editor locks the claim for other users until it is saved or cancelled, or the
-- sheet's today changes; none when the claim is not locked.
ALTER TABLE claim ADD COLUMN locked_by VARCHAR(255);
