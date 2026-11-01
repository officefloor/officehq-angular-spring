package net.officefloor.hq.app.crew;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMemberRepository extends JpaRepository<CrewMember, Long> {

    List<CrewMember> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsByCrewIdAndPersonId(Long crewId, Long personId);
}
