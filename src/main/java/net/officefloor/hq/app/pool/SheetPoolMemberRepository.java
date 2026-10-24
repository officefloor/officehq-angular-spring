package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPoolMemberRepository extends JpaRepository<SheetPoolMember, Long> {

    List<SheetPoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);
}
