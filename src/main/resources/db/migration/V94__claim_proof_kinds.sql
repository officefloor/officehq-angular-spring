-- How many of a claim's proofs are photos; the rest of its proofs are receipts (a proof added with no
-- kind chosen is a receipt).
ALTER TABLE claim ADD COLUMN proof_photos INT DEFAULT 0 NOT NULL;
