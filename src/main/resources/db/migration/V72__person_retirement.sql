-- A retired person can be unretired. Each past retirement is kept, from its from-date to the
-- sheet's today when they were unretired; no claim of theirs can be dated strictly between the two.
-- made_on is the sheet's today when the retirement was recorded (none: from the beginning of time).
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL,
    made_on   DATE
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
