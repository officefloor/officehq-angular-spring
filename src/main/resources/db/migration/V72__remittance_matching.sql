-- The client's purchase order number on the invoice (null when none was given).
ALTER TABLE invoice ADD COLUMN po_number VARCHAR(100);
-- The remittance text the client wrote on the payment (null when none was given).
ALTER TABLE payment ADD COLUMN remittance VARCHAR(255);
-- The invoice whose purchase order number the remittance matched (null when none) and how closely (Jaro-Winkler).
ALTER TABLE payment ADD COLUMN matched_invoice BIGINT;
ALTER TABLE payment ADD COLUMN match_score DECIMAL(4, 3);
