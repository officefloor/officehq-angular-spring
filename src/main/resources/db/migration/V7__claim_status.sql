-- A claim's status: DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL;
ALTER TABLE claim ADD CONSTRAINT claim_status CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
