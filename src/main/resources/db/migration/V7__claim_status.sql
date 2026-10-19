-- A claim's status: it starts as DRAFT, is opened (OPEN) and then settled (SETTLED); a DRAFT or
-- OPEN claim can be voided (VOID). Only OPEN and SETTLED claims count toward what is claimed.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) DEFAULT 'DRAFT' NOT NULL
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
