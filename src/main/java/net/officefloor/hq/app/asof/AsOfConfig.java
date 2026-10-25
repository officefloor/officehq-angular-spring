package net.officefloor.hq.app.asof;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires the as-of view: the transaction manager that keeps each sheet's history. */
@Configuration
public class AsOfConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
            ObjectProvider<SheetHistory> history) {
        return new HistoryTransactionManager(entityManagerFactory, history);
    }
}
