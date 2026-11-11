-- Each plan date keeps the claim the plan made for it, so changing the plan's amount can change the
-- claims it generated that are still DRAFT. Null for the dates made before this was kept.
ALTER TABLE plan_date ADD COLUMN claim_id BIGINT;
