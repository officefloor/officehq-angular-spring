package net.officefloor.hq.app.sheet;

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
 * Refuses every change asked for on an archived sheet: any request on it other than a read is refused before it
 * reaches its handler, so it writes nothing. Loading the sheet and the bulk actions (taken with every selected item
 * refused) are left to their handlers; see {@link ArchiveGuardConfig}.
 */
@Component
public class ArchiveGuard implements HandlerInterceptor {

    private final SheetService sheets;

    public ArchiveGuard(SheetService sheets) {
        this.sheets = sheets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String sheetId = sheetId(request);
        if (sheetId != null && sheets.isArchived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived: nothing on it can change");
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
