package net.officefloor.hq.app.asof;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Routes the app's data source (see {@link AsOfDataSource}), so a request answered as of a date reads the sheet as it then stood. */
@Configuration
public class AsOfConfig {

    @Bean
    public static BeanPostProcessor asOfDataSourceRouting() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds && !(bean instanceof AsOfDataSource) ? new AsOfDataSource(ds)
                        : bean;
            }
        };
    }
}
