package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    /** The members of the pools, each pool's in the order they were added. */
    List<PoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    /** The person's memberships, in the order they were added. */
    List<PoolMember> findByPersonIdOrderByIdAsc(Long personId);

    java.util.Optional<PoolMember> findByPoolIdAndPersonId(Long poolId, Long personId);

    /** Where a pool is a member of another: at most once. */
    List<PoolMember> findByMemberPoolId(Long memberPoolId);

    boolean existsByMemberPoolId(Long memberPoolId);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);
}
