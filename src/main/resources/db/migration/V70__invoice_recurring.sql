-- Whether the invoice repeats every period: a sent recurring invoice can generate the next period's draft.
ALTER TABLE invoice ADD COLUMN recurring BOOLEAN NOT NULL DEFAULT FALSE;
-- The id of the recurring invoice this draft was generated from as its next period; null when it was not.
ALTER TABLE invoice ADD COLUMN recurring_from BIGINT;
