package net.officefloor.hq.app.claim;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimSequenceRepository extends JpaRepository<ClaimSequence, ClaimSequence.Key> {
}
