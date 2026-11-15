-- A person's own terms are dated: person.terms are those in force from the beginning of time, and
-- each later save with a from-date is kept here from that date (one per from-date). A claim uses
-- the terms in force on its date: the ones with the latest from-date on or before it. Terms of -1
-- record that from that date the person has none of their own (their grade's or the sheet's apply).
CREATE TABLE person_terms (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    terms     INT    NOT NULL CHECK (terms >= -1),
    PRIMARY KEY (person_id, from_date)
);
