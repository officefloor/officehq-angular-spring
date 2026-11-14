-- A claim's status: DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim may be
-- VOID. Only OPEN and SETTLED claims count toward claimed and due totals.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
