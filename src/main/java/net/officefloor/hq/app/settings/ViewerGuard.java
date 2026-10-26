package net.officefloor.hq.app.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses a viewer every request that changes a sheet, before it reaches its handler, so it writes nothing. A
 * handler marked {@link ViewerPasses} is let through.
 */
@Component
public class ViewerGuard implements HandlerInterceptor {

    private final SettingsService settings;

    public ViewerGuard(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || HttpMethod.GET.matches(request.getMethod())
                || method.hasMethodAnnotation(ViewerPasses.class)) {
            return true;
        }
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SessionController.USER);
        String sheetId = sheetId(request);
        if (user instanceof String name && sheetId != null && settings.isViewer(sheetId, name)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may not change anything.");
        }
        return true;
    }

    /** The sheet the request is on: its sheetId path variable, or the id of a /api/sheets/{id} path. */
    private static String sheetId(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) {
            return null;
        }
        String sheetId = vars.get("sheetId");
        if (sheetId == null && request.getRequestURI().startsWith(request.getContextPath() + "/api/sheets/")) {
            sheetId = vars.get("id");
        }
        return sheetId;
    }
}
