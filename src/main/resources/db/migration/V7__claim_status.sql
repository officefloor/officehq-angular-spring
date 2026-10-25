-- A claim's status: DRAFT -> OPEN -> SETTLED, and DRAFT or OPEN -> VOID. Only OPEN and SETTLED
-- claims count toward what a person has claimed and what is due.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
