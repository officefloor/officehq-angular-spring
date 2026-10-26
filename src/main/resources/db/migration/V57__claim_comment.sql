-- A claim's comments, in the order they were written: the text, the user who wrote it and the sheet's today then.
CREATE TABLE claim_comment (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT       NOT NULL REFERENCES claim (id),
    by_user  VARCHAR(100) NOT NULL,
    on_date  DATE,
    text     VARCHAR(500) NOT NULL
);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id, id);
