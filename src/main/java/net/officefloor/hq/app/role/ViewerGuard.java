package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A viewer sees the sheet and changes nothing: every change to a sheet by a viewer is refused before it
 * reaches its handler. Loading the sheet is not a change, and a bulk action goes through with nothing done
 * (each claim refused), so those are left to their handlers.
 */
@Configuration
public class ViewerGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final RoleService roles;

    public ViewerGuard(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/claims/bulk-*");
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
            roles.requireNotViewer(sheetId.trim(), user.trim());
        }
        return true;
    }
}
