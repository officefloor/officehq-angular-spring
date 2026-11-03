-- A person's own terms can be dated: each applies from its from-date until a later from-date of the
-- person takes over; terms of null there mean none (the grade's or the sheet's apply). Before the
-- earliest from-date the person's undated terms (person.terms) apply. At most one per from-date.
CREATE TABLE person_terms (
    person_id BIGINT NOT NULL REFERENCES person (id),
    from_date DATE   NOT NULL,
    terms     INT,
    PRIMARY KEY (person_id, from_date)
);
-- A person revision keeps the dated terms as "from:terms" (terms blank when none), separated by ";".
ALTER TABLE person_revision ADD COLUMN terms_from VARCHAR(4000) DEFAULT '' NOT NULL;
