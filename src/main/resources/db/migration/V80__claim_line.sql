-- The lines of a claim, oldest first: a label, a whole quantity of at least 1 and a unit amount; a
-- claim with lines has the quantities times the unit amounts added up as its amount.
CREATE TABLE claim_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    label VARCHAR(255) NOT NULL,
    qty INT NOT NULL CHECK (qty >= 1),
    unit_amount DECIMAL(12, 2) NOT NULL
);
CREATE INDEX claim_line_claim ON claim_line (claim_id, id);
