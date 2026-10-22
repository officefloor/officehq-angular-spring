package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.SignedIn;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Marks each change a user asks for on a sheet while a delegation to them is in force on the sheet's
 * today with the steward they act for, so every audit record of the change names that steward.
 */
@Configuration
public class DelegationGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final DelegationService delegations;

    public DelegationGuard(DelegationService delegations) {
        this.delegations = delegations;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**").excludePathPatterns("/api/sheets/*/load");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(SignedIn.HEADER);
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (header == null || !(variables instanceof Map<?, ?> vars)) {
            return true;
        }
        Object sheetId = vars.containsKey("sheetId") ? vars.get("sheetId") : vars.get("id");
        if (sheetId instanceof String id) {
            delegations.actingFor(id, SignedIn.name(header))
                    .ifPresent(steward -> request.setAttribute(Audit.ACTING_FOR, steward));
        }
        return true;
    }
}
