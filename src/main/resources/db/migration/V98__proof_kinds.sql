-- A proof has a kind, receipt or photo (receipt when none is chosen): a claim counts its photos beside its proofs,
-- the rest being receipts (every proof given before kinds is a receipt). The sheet's proof kinds (comma separated,
-- not dated; null means none): while set, a claim above the proof threshold needs a proof of each before it can be
-- settled or approved.
ALTER TABLE claim ADD COLUMN proof_photos INTEGER NOT NULL DEFAULT 0;
ALTER TABLE sheet ADD COLUMN proof_kinds VARCHAR(200);
