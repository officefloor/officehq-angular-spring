-- A claim's status: DRAFT when added; opened (OPEN), then settled (SETTLED); a DRAFT or OPEN
-- claim can be voided (VOID). Only OPEN and SETTLED claims count toward the totals.
ALTER TABLE claim ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
    CHECK (status IN ('DRAFT', 'OPEN', 'SETTLED', 'VOID'));
