-- A person may carry tags, kept as a claim's are: lower case, trimmed, no repeats, sorted and comma
-- separated (no spaces); null when the person has none. Their tags go on the sheet's list of tags too.
ALTER TABLE person ADD COLUMN tags VARCHAR(2000);
