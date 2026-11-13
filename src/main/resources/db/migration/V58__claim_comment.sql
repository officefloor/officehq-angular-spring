-- Comments on a claim, oldest first: who wrote each, the sheet's today when they did, and its text.
CREATE TABLE claim_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    written_by VARCHAR(255) NOT NULL,
    written_on DATE NOT NULL,
    text VARCHAR(2000) NOT NULL
);
CREATE INDEX claim_comment_sheet ON claim_comment (sheet_id, id);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id, id);
