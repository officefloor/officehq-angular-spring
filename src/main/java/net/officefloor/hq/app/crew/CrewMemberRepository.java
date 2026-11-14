package net.officefloor.hq.app.crew;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMemberRepository extends JpaRepository<CrewMember, Long> {

    /** The members of these crews, in the order they were added. */
    List<CrewMember> findByCrewIdInOrderByIdAsc(Collection<Long> crewIds);

    /** A person's memberships, in the order they were added. */
    List<CrewMember> findByPersonIdOrderByIdAsc(Long personId);

    /** Whether the person is a member of the crew. */
    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
