package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses every change a viewer asks of a sheet: any request but a read, except loading the sheet and the bulk
 * actions, which go through for a viewer with nothing done and every selected item refused.
 */
@Component
public class ViewerInterceptor implements HandlerInterceptor {

    /** The changing requests a viewer may still make: their handlers decide what a viewer gets. */
    private static final Set<String> LET_THROUGH = Set.of("/load", "/claims/bulk-open", "/claims/bulk-settle",
            "/claims/bulk-reassign",
            "/people/bulk-grade");

    private final RoleService roles;

    public ViewerInterceptor(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (LET_THROUGH.stream().anyMatch(path::endsWith)) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String user = request.getHeader("X-User");
        if (variables == null || user == null) {
            return true;
        }
        String sheetId = variables.containsKey("sheetId") ? variables.get("sheetId") : variables.get("id");
        if (sheetId == null) {
            return true;
        }
        if (roles.isViewer(sheetId.trim(), user.trim())) {
            throw new ViewerForbiddenException(sheetId.trim(), user.trim());
        }
        return true;
    }
}
