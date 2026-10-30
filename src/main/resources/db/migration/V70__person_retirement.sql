-- A person's past retirements, each from the date they were retired from to the day they were unretired; a
-- person may have several over time. No claim dated after the from-date and before the to-date of one can be
-- added for them, as for a claim after a retirement.
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
