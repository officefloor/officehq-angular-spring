-- How much of the retention has been released in all, in the invoice's currency. Null with a release
-- day is a release recorded before partial releases: everything that was held was released.
ALTER TABLE invoice ADD COLUMN retention_released_amount DECIMAL(19, 2);
