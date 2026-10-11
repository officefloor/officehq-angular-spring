-- The client's grace period: days past its due date an invoice or instalment is given before it is
-- overdue, earns late interest and is charged the late fee (0 to 30; the standard is a week).
ALTER TABLE client ADD COLUMN grace_days INT DEFAULT 7 NOT NULL;
