-- A retired person can be unretired: they are no longer retired, and the retirement is kept as a
-- past one, from its date to the day they were unretired. A person may have several over time; no
-- claim dated after the from and before the to of one can be added for them.
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    retirement_order INT NOT NULL,
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL,
    PRIMARY KEY (person_id, retirement_order)
);
