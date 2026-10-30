-- A claim's status log: one line per status it has had, oldest first, with who made the change, the
-- sheet's today then and the claim's due as it was then (null when it had none). A claim already there
-- starts with no lines.
CREATE TABLE claim_log (
    claim_id BIGINT NOT NULL REFERENCES claim(id),
    seq INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    by_user VARCHAR(200) NOT NULL,
    on_date DATE NOT NULL,
    due DECIMAL(14, 2),
    PRIMARY KEY (claim_id, seq)
);
