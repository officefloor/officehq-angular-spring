-- A person's own terms are dated: person.terms are those in force from the beginning of time (null:
-- none); each set recorded from a from-date is a row here, in force from it until the next one. One per
-- person and from-date; terms -1 records none from that date.
CREATE TABLE person_terms (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE    NOT NULL,
    terms     INTEGER NOT NULL,
    PRIMARY KEY (person_id, from_date)
);
