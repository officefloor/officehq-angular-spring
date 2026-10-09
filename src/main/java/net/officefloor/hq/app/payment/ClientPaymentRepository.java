package net.officefloor.hq.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientPaymentRepository extends JpaRepository<ClientPayment, Long> {
}
