-- The numbers a claim had before it was re-dated into another period's sequence; never given out again.
CREATE TABLE claim_former_number (
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    former_number VARCHAR(255) NOT NULL
);
CREATE INDEX claim_former_number_claim ON claim_former_number (claim_id);
