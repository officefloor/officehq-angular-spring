-- A person's patrons are kept in the order they were named: their shares are split in that order.
-- Patrons named before this are taken in the order of their ids.
ALTER TABLE person_patron ADD COLUMN patron_order INT;
UPDATE person_patron pp SET patron_order = (
    SELECT COUNT(*) FROM person_patron q WHERE q.person_id = pp.person_id AND q.patron_id < pp.patron_id);
ALTER TABLE person_patron ALTER COLUMN patron_order SET NOT NULL;
ALTER TABLE person_patron DROP PRIMARY KEY;
ALTER TABLE person_patron ADD PRIMARY KEY (person_id, patron_order);
