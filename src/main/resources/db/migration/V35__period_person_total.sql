-- Each person's due-total for a closed period, fixed when it closed: the dues of their claims that count dated
-- in it. A person with none has no line, and shows 0.00. Reopening the period removes its lines.
CREATE TABLE period_person_total (
    period_id BIGINT         NOT NULL REFERENCES period (id),
    person_id BIGINT         NOT NULL REFERENCES person (id),
    total     DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (period_id, person_id)
);
