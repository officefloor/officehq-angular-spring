package net.officefloor.hq.app.pool;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoolMemberRepository extends JpaRepository<PoolMember, Long> {

    List<PoolMember> findBySheetIdOrderByIdAsc(String sheetId);

    boolean existsByPoolIdAndPersonId(Long poolId, Long personId);

    Optional<PoolMember> findByMemberPoolId(Long memberPoolId);
}
