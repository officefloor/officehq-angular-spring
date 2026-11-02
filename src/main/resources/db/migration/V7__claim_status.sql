-- A claim's status: DRAFT -> OPEN -> SETTLED, and a DRAFT or OPEN claim may be VOID.
-- Only OPEN and SETTLED claims count toward what a person has claimed.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
