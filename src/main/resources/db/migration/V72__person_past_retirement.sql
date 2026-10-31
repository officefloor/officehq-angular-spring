-- A person's past retirements: each from its from-date to the day they were unretired. A claim of theirs dated
-- after the from-date and before the to-date is refused, as one after a retirement is.
CREATE TABLE person_past_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL
);
CREATE INDEX person_past_retirement_person ON person_past_retirement (person_id);
