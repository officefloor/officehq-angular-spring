package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A user delegated the steward role acts for the steward who delegated it: each change they make while the
 * sheet's today is inside the delegation notes that steward on the request, for the audit to end its lines
 * with for=<steward>.
 */
@Configuration
public class ActingForGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final RoleService roles;

    public ActingForGuard(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String user = request.getHeader("X-User");
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (user != null && sheetId != null) {
            String steward = roles.actingFor(sheetId.trim(), user.trim());
            if (steward != null) {
                request.setAttribute(Audit.ACTING_FOR, steward);
            }
        }
        return true;
    }
}
