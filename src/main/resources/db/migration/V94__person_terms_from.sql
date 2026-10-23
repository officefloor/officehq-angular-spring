-- A person's own terms are dated: the terms on the person row apply from the beginning of time; each
-- later terms are recorded here from their date, one per date, and hold from that date until the next.
-- A terms of -1 records that the person has no terms of their own from that date.
CREATE TABLE person_terms (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE    NOT NULL,
    terms     INTEGER NOT NULL CHECK (terms >= -1),
    PRIMARY KEY (person_id, from_date)
);
