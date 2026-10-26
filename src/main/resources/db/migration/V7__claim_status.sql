-- A claim's status: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT';
