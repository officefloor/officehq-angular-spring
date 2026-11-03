-- The claims a plan has generated (on its own sheet or the descendant it names), each at most once;
-- a change of the plan's amount reaches those still in draft.
CREATE TABLE plan_claim (
    plan_id  BIGINT NOT NULL REFERENCES plan (id) ON DELETE CASCADE,
    claim_id BIGINT NOT NULL,
    CONSTRAINT plan_claim_unique UNIQUE (plan_id, claim_id)
);
