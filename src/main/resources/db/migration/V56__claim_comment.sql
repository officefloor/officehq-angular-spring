-- A claim's comments, oldest first (by seq): each with who wrote it, the sheet's today when they did and its text.
CREATE TABLE claim_comment (
    claim_id BIGINT        NOT NULL REFERENCES claim (id),
    seq      INT           NOT NULL,
    by_user  VARCHAR(100)  NOT NULL,
    on_date  DATE          NOT NULL,
    comment_text VARCHAR(1000) NOT NULL,
    PRIMARY KEY (claim_id, seq)
);
