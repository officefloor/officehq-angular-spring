package net.officefloor.hq.app.crew;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMemberRepository extends JpaRepository<CrewMember, Long> {

    /** The members of the crews, each crew's in the order they were added. */
    List<CrewMember> findByCrewIdInOrderByIdAsc(Collection<Long> crewIds);

    /** Whether the person is already a member of the crew. */
    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
