-- A person's own terms, each from a from-date until their next; null terms: none from that date.
-- The person's terms column holds the ones from the beginning of time.
CREATE TABLE person_terms (
    person_id BIGINT  NOT NULL REFERENCES person (id),
    from_date DATE    NOT NULL,
    terms     INTEGER CHECK (terms >= 0),
    CONSTRAINT person_terms_once UNIQUE (person_id, from_date)
);
CREATE INDEX person_terms_person ON person_terms (person_id);
