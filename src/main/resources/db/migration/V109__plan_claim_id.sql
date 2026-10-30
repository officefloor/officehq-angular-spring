-- The claim a plan date was made as, so a change of the plan's amount can reach the claims it generated that are
-- still DRAFT (null for the plan dates generated before it was kept).
ALTER TABLE sheet_plan_claim ADD COLUMN claim_id BIGINT;
