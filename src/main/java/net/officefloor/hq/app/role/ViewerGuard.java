package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses every change a viewer asks for on a sheet: any request on a sheet other than a read is refused before
 * it reaches its handler, so it writes nothing. Loading the sheet and the bulk actions (which a viewer takes with
 * every selected item refused) are left to their handlers; see {@link ViewerGuardConfig}.
 */
@Component
public class ViewerGuard implements HandlerInterceptor {

    private final RoleService roles;

    public ViewerGuard(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String user = request.getHeader("X-User");
        String sheetId = sheetId(request);
        if (user == null || sheetId == null) {
            return true;
        }
        if (roles.isViewer(sheetId, user.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer cannot change anything on this sheet");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String sheetId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> path = (Map<String, String>) map;
        return path.containsKey("sheetId") ? path.get("sheetId") : path.get("id");
    }
}
