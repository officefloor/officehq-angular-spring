package net.officefloor.hq.app.pool;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    /** The members of these pools, in the order they were added. */
    List<PoolMember> findByPoolIdInOrderByIdAsc(Collection<Long> poolIds);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);

    /** A person's memberships, in the order they were added. */
    List<PoolMember> findByPersonIdOrderByIdAsc(Long personId);

    Optional<PoolMember> findByPoolIdAndPersonId(Long poolId, Long personId);
}
