package net.officefloor.hq.app.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses every change a viewer asks for on a sheet, and every change anyone asks for on an archived sheet
 * (403, writing nothing). Reading, and loading a sheet, go through; so do the bulk actions, which answer
 * with nothing done and every selected item refused.
 */
@Component
public class ViewerGuard implements HandlerInterceptor {

    private static final String REFUSAL = "{\"message\":\"A viewer can change nothing.\"}";

    private static final String ARCHIVED = "{\"message\":\"The sheet is archived: nothing on it can change.\"}";

    private final AccessService access;

    public ViewerGuard(AccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.endsWith("/load") || path.contains("/bulk-")) {
            return true;
        }
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String user = request.getHeader("X-User");
        if (!(vars instanceof Map<?, ?> v) || user == null) {
            return true;
        }
        Object sheet = v.containsKey("sheetId") ? v.get("sheetId") : v.get("id");
        if (!(sheet instanceof String id)) {
            return true;
        }
        String refusal = access.isArchived(id.trim()) ? ARCHIVED
                : access.isViewer(id.trim(), user.trim()) ? REFUSAL : null;
        if (refusal == null) {
            return true;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(refusal);
        return false;
    }
}
