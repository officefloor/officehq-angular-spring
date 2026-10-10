-- A client can be put into a segment (e.g. "VIP"), so the office can see how many clients are in each;
-- existing clients are in none.
ALTER TABLE client ADD COLUMN segment VARCHAR(50);
