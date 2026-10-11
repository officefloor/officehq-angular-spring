-- The interest of the instalments not yet due that was dropped when a plan was paid off early; 0 when
-- it was not.
ALTER TABLE invoice ADD COLUMN interest_saved DECIMAL(19, 2) DEFAULT 0 NOT NULL;
