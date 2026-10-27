-- A retired person may be unretired: their retirement is kept as a past one, from its from-date to the sheet's today
-- when they were unretired, and they are no longer retired. A claim dated after the from-date and before the to-date of
-- a past retirement is refused, as one after a retirement is.
CREATE TABLE person_retirement (
    person_id  BIGINT NOT NULL REFERENCES person (id),
    from_date  DATE   NOT NULL,
    to_date    DATE   NOT NULL,
    retired_on DATE,
    made_on    DATE
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
