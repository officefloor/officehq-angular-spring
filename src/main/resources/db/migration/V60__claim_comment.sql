-- A claim's comments, one row each, oldest first by id: the text, the user who wrote it and the
-- sheet's today when it was written.
CREATE TABLE claim_comment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id     BIGINT NOT NULL REFERENCES claim (id),
    by_user      VARCHAR(100) NOT NULL,
    comment_date DATE NOT NULL,
    text         VARCHAR(1000) NOT NULL
);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id);
