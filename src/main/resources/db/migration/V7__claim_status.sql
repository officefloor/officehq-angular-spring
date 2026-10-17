-- A claim's status: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim may be VOID instead.
-- Only OPEN and SETTLED claims count toward the totals.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
