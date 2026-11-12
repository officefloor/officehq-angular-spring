-- A claim's status: DRAFT when added; DRAFT -> OPEN -> SETTLED, and DRAFT or OPEN -> VOID.
-- Only OPEN and SETTLED claims count toward the totals.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
