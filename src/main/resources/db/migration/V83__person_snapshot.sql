-- Each SNAPSHOT line written for a person when the sheet's today changes: the day being left and their
-- due-total on it (null: n/a), kept in the order written (seq).
CREATE TABLE person_snapshot (
    person_id BIGINT NOT NULL REFERENCES person (id),
    seq       INT NOT NULL,
    on_date   DATE NOT NULL,
    due_total DECIMAL(14, 2),
    PRIMARY KEY (person_id, seq)
);
