-- A claim's comments, in the order they were written (by id). Each keeps who wrote it, the sheet's
-- today then, and its (trimmed, never empty) text.
CREATE TABLE claim_comment (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100)   NOT NULL REFERENCES sheet (id),
    claim_id  BIGINT         NOT NULL REFERENCES claim (id),
    by_user   VARCHAR(100)   NOT NULL,
    on_date   DATE           NOT NULL,
    text      VARCHAR(1000)  NOT NULL
);
CREATE INDEX claim_comment_claim ON claim_comment (claim_id, id);
