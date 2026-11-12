-- A person's own terms may be dated: each dated record applies from its from-date on, until the
-- person's next later-dated one takes over; a record without terms clears them from that date (the
-- grade's or the sheet's apply). person.terms stays the terms from the beginning of time. A person
-- has at most one record per from-date.
CREATE TABLE person_terms (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person (id),
    terms     INT,
    from_date DATE   NOT NULL
);
CREATE UNIQUE INDEX person_terms_person_from ON person_terms (person_id, from_date);
