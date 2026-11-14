-- The people a claim is shared with, in the order it was shared: each as
-- <person id>:<share percent>:<the sheet's today when it was shared>, comma separated; null for none.
ALTER TABLE claim ADD COLUMN shares VARCHAR(4000);
