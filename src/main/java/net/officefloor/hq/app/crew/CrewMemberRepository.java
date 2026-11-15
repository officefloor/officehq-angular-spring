package net.officefloor.hq.app.crew;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMemberRepository extends JpaRepository<CrewMember, Long> {

    /** The crews' members in the order they were added. */
    List<CrewMember> findByCrewIdInOrderByIdAsc(Collection<Long> crewIds);

    /** The person's memberships, in the order they were added. */
    List<CrewMember> findByPersonIdOrderByIdAsc(Long personId);

    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
