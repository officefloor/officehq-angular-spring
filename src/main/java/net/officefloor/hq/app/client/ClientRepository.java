package net.officefloor.hq.app.client;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /** All clients not archived, in id order. */
    List<Client> findByArchivedFalseOrderById();

    /** Whether any client, archived or not, already has the email, ignoring case. */
    boolean existsByEmailIgnoreCase(String email);

    /** Whether any client other than the given one already has the email, ignoring case. */
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    /** Clients not archived whose name contains the text, ignoring case, in id order. */
    @Query("SELECT c FROM Client c WHERE c.archived = false AND LOWER(c.name) LIKE LOWER(CONCAT('%', :text, '%'))"
            + " ESCAPE '\\' ORDER BY c.id")
    List<Client> searchActiveByName(String text);

    /** Moves every project of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Project p SET p.client.id = :to WHERE p.client.id = :from")
    int moveProjects(Long from, Long to);

    /** Moves every contact of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Contact c SET c.client.id = :to WHERE c.client.id = :from")
    int moveContacts(Long from, Long to);

    /** Moves every lump payment of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ClientPayment p SET p.clientId = :to WHERE p.clientId = :from")
    int moveClientPayments(Long from, Long to);

    /** Moves every deposit of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Deposit d SET d.clientId = :to WHERE d.clientId = :from")
    int moveDeposits(Long from, Long to);

    /** Moves every deposit application of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DepositApplication a SET a.clientId = :to WHERE a.clientId = :from")
    int moveDepositApplications(Long from, Long to);

    /** Moves every refund of one client over to another. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Refund r SET r.clientId = :to WHERE r.clientId = :from")
    int moveRefunds(Long from, Long to);
}
