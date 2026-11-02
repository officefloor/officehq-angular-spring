package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.function.Supplier;
import net.officefloor.hq.app.Audit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Marks a change asked of a sheet by a user the steward role is delegated to on the sheet's today
 *  (as of when each line is written), so its audit lines end with the steward who delegated it (see {@link Audit#ACTING_FOR}). */
@Configuration
public class ActingFor implements HandlerInterceptor, WebMvcConfigurer {

    private final RoleService roles;

    public ActingFor(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String user = request.getHeader("X-User");
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (user != null && sheetId != null) {
            String sheet = sheetId.trim();
            String name = user.trim();
            Supplier<String> actingFor = () -> roles.delegatorOf(sheet, name).orElse(null);
            request.setAttribute(Audit.ACTING_FOR, actingFor);
        }
        return true;
    }
}
