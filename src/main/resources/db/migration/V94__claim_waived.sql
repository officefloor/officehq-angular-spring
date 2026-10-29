-- The sheet's today on which a steward waived an OPEN or PENDING claim's late charge: from that day on the claim bears
-- no late charge, whatever its overdue state. Null for a claim never waived.
ALTER TABLE claim ADD COLUMN waived_on DATE;
