-- The plan fee charged when the invoice was spread over instalments: the workspace's plan fee in US
-- dollars converted into the client's currency at the issue date's rate, rounded down; added to what
-- is spread over the instalments. 0 when there is none.
ALTER TABLE invoice ADD COLUMN plan_fee DECIMAL(19, 2) DEFAULT 0 NOT NULL;
