-- A promise to pay on an overdue invoice: the amount promised, the day it is promised by, and the day it was made.
ALTER TABLE invoice ADD COLUMN promise_amount DECIMAL(19, 2);
ALTER TABLE invoice ADD COLUMN promise_date DATE;
ALTER TABLE invoice ADD COLUMN promised_on DATE;
