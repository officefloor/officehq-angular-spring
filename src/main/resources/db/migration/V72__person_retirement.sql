-- A retired person can be unretired: the retirement is kept as a past one, from its from-date to
-- the day they were unretired, and a person may have several over time. No claim dated after the
-- from-date and before the to-date of a past retirement can be added for them (or moved there).
CREATE TABLE person_retirement (
    person_id BIGINT NOT NULL REFERENCES person (id),
    position  INT    NOT NULL,
    from_date DATE   NOT NULL,
    to_date   DATE   NOT NULL,
    PRIMARY KEY (person_id, position)
);
