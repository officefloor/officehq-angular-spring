-- A claim's status: DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim may be VOID.
-- Only OPEN and SETTLED claims count toward what is claimed and due.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
