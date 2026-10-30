package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    List<PoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    List<PoolMember> findByPersonId(Long personId);

    boolean existsByMemberPoolId(Long memberPoolId);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);
}
