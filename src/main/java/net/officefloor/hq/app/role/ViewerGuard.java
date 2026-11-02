package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses every change a viewer asks of a sheet (403), before it writes anything; only the
 *  requests marked {@link ViewerAllowed} go through. */
@Configuration
public class ViewerGuard implements HandlerInterceptor, WebMvcConfigurer {

    private final RoleService roles;

    public ViewerGuard(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || !(handler instanceof HandlerMethod method)
                || method.hasMethodAnnotation(ViewerAllowed.class)) {
            return true;
        }
        String user = request.getHeader("X-User");
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (user != null && sheetId != null
                && SheetRole.VIEWER.equals(roles.roleOf(sheetId.trim(), user.trim()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may change nothing");
        }
        return true;
    }
}
