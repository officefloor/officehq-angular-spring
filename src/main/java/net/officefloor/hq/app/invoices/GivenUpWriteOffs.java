package net.officefloor.hq.app.invoices;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Before any API request is handled, writes off the invoices given up on by today (see
 * {@link InvoiceService#writeOffGivenUp}), so everything the request reads or does sees them written off.
 */
@Configuration
public class GivenUpWriteOffs implements WebMvcConfigurer, HandlerInterceptor {

    private final InvoiceService invoices;

    public GivenUpWriteOffs(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        invoices.writeOffGivenUp();
        return true;
    }
}
