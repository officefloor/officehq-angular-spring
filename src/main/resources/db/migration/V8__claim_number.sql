-- A claim's number: given when it is opened, the next on its sheet (1, 2, ...), never reused. A
-- draft has none, and voiding clears it. The sheet keeps the last number it gave out.
ALTER TABLE claim ADD COLUMN number INT;
ALTER TABLE sheet ADD COLUMN last_claim_number INT DEFAULT 0 NOT NULL;
UPDATE claim c SET number = (
    SELECT COUNT(*) FROM claim o
    WHERE o.sheet_id = c.sheet_id AND o.status IN ('OPEN', 'SETTLED') AND o.id <= c.id)
WHERE c.status IN ('OPEN', 'SETTLED');
UPDATE sheet s SET last_claim_number = COALESCE((SELECT MAX(number) FROM claim c WHERE c.sheet_id = s.id), 0);
CREATE UNIQUE INDEX claim_sheet_number ON claim (sheet_id, number);
