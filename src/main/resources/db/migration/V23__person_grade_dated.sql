-- A person's grade is dated: person.grade is the first grade they were given, applying from the
-- beginning of time, and each later grade is kept here from its from-date (one per from-date). A
-- claim uses the grade in force on its date: the one with the latest from-date on or before it.
CREATE TABLE person_grade (
    person_id BIGINT     NOT NULL REFERENCES person (id),
    from_date DATE       NOT NULL,
    grade     VARCHAR(1) NOT NULL CHECK (grade IN ('A', 'B', 'C')),
    PRIMARY KEY (person_id, from_date)
);
