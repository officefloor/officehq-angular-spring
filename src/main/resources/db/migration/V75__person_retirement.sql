-- A person's past retirements: each kept from the day they were retired from to the day they were
-- unretired. No claim of theirs may be dated after the from-date and before the to-date of one.
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL
);
CREATE INDEX person_retirement_person ON person_retirement (person_id);

-- A person revision keeps the past retirements as text: each "from..to", separated by ";".
ALTER TABLE person_revision ADD COLUMN retirements VARCHAR(4000) DEFAULT '' NOT NULL;
