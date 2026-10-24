package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetPoolMemberRepository extends JpaRepository<SheetPoolMember, Long> {

    List<SheetPoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    List<SheetPoolMember> findByPersonIdOrderByIdAsc(Long personId);

    Optional<SheetPoolMember> findByPoolIdAndPersonId(Long poolId, Long personId);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);
}
