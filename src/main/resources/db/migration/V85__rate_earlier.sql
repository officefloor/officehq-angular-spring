-- A rate saved with a from-date on or after the sheet's today is forward-only: the claims already on
-- the sheet keep the rate that applied to them. The rate row keeps, for those earlier claims, what they
-- use instead, as entries separated by ';' oldest first, each 'upTo:rate:to': a claim whose id is at most
-- upTo (and above the entry before's) uses that rate ('-' for none of this grade and from-date) through
-- that to-date (blank for none). A claim above every entry uses the rate in the row; none kept when the
-- rate applies to every claim.
ALTER TABLE rate ADD COLUMN earlier VARCHAR(1000000);
