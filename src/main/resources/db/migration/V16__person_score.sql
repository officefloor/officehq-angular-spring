-- Notes fold into a single whole-number score on each person (absent until given); a person's
-- existing notes become their score as the notes' total.
ALTER TABLE person ADD COLUMN score INT;
UPDATE person p SET score = (SELECT SUM(pn.score) FROM person_note pn WHERE pn.person_id = p.id)
    WHERE EXISTS (SELECT 1 FROM person_note pn WHERE pn.person_id = p.id);
DROP TABLE person_note;
