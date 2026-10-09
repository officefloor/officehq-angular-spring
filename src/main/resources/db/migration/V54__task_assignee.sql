-- A task may name the person it is for.
ALTER TABLE task ADD COLUMN assignee VARCHAR(255);
