package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    /** The members of the pools, each pool's in the order they were added. */
    List<PoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    /** Whether the person is already a member of the pool. */
    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);

    /** Whether the pool is already a member of a pool. */
    boolean existsByMemberPoolId(Long memberPoolId);
}
