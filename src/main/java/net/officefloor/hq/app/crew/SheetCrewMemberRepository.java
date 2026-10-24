package net.officefloor.hq.app.crew;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetCrewMemberRepository extends JpaRepository<SheetCrewMember, Long> {

    List<SheetCrewMember> findByCrewIdInOrderByIdAsc(Collection<Long> crewIds);

    List<SheetCrewMember> findByCrewIdOrderByIdAsc(Long crewId);

    List<SheetCrewMember> findByPersonIdOrderByIdAsc(Long personId);

    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
