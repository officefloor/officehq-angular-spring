package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    /** The pools' members in the order they were added. */
    List<PoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    /** The person's memberships, in the order they were added. */
    List<PoolMember> findByPersonIdOrderByIdAsc(Long personId);

    Optional<PoolMember> findByPoolIdAndPersonId(Long poolId, Long personId);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);
}
