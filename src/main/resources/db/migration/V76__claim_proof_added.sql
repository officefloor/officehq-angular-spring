-- The day each proof was given to a claim, so the sheet as it stood at the end of a day counts only
-- the proofs given by then. Proofs given before this was kept have no day: they count on every day.
CREATE TABLE claim_proof (
    claim_id BIGINT NOT NULL REFERENCES claim (id),
    added_on DATE   NOT NULL
);
CREATE INDEX claim_proof_claim ON claim_proof (claim_id);
