-- The past retirements of a person who was unretired, each from its from-date to the day they were
-- unretired: a claim of theirs dated after the from-date and before the to-date is refused.
CREATE TABLE person_retirement (
    person_id    BIGINT NOT NULL REFERENCES person (id),
    retired_from DATE   NOT NULL,
    retired_to   DATE   NOT NULL
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);
