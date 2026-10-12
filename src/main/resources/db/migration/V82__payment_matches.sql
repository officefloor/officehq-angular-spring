-- Each part of a payment's remittance (separated by semicolons) may match an invoice's purchase order number:
-- the invoices matched, in the order of the parts, and how closely (Jaro-Winkler).
CREATE TABLE payment_match (
    payment_id BIGINT       NOT NULL REFERENCES payment (id) ON DELETE CASCADE,
    position   INT          NOT NULL,
    invoice_id BIGINT       NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    score      DECIMAL(4, 3) NOT NULL,
    PRIMARY KEY (payment_id, position)
);
INSERT INTO payment_match (payment_id, position, invoice_id, score)
    SELECT id, 0, matched_invoice, match_score FROM payment WHERE matched_invoice IS NOT NULL;
ALTER TABLE payment DROP COLUMN matched_invoice;
ALTER TABLE payment DROP COLUMN match_score;
