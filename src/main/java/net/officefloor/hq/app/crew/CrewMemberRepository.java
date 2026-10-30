package net.officefloor.hq.app.crew;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMemberRepository extends JpaRepository<CrewMember, Long> {

    List<CrewMember> findByCrewIdInOrderByIdAsc(Collection<Long> crewIds);

    List<CrewMember> findByCrewId(Long crewId);

    List<CrewMember> findByPersonId(Long personId);

    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
