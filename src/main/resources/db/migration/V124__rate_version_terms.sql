-- An older version of a rate keeps the terms the rate had while it was the newest (see rate.terms), so
-- withdrawing the newer version can bring it back exactly as it applied to claims. Null: it applied to
-- every claim (and so for every version kept before this column).
ALTER TABLE rate_version ADD COLUMN terms VARCHAR(4000);
