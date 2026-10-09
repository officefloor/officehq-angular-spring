-- A project can carry a budget to invoice against; existing projects have none set.
ALTER TABLE project ADD COLUMN budget DECIMAL(12, 2);

ALTER TABLE project ADD CONSTRAINT project_budget_not_negative CHECK (budget IS NULL OR budget >= 0);
