-- The claim a plan date made, so a change of the plan's amount reaches the claims it generated that are still DRAFT.
-- The claim may be on a descendant sheet; null for plan dates made before this was kept.
ALTER TABLE plan_date ADD COLUMN claim_id BIGINT;
