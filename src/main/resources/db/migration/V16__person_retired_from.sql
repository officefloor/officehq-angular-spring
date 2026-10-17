-- The date a person is retired from; null while they are not retired. A claim dated after it is refused.
ALTER TABLE person ADD COLUMN retired_from DATE;
