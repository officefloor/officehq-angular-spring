-- A claim's comments, oldest first (by id): the text as written (trimmed, never blank), with the user
-- who wrote it and the sheet's today when it was written.
CREATE TABLE claim_comment (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id   BIGINT         NOT NULL REFERENCES claim (id),
    by_user    VARCHAR(200)   NOT NULL,
    on_date    DATE           NOT NULL,
    text       VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id, id);
