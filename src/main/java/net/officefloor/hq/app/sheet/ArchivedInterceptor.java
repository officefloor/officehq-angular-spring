package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Refuses every change asked of an archived sheet: any request but a read, except loading the sheet, archiving it
 * and the bulk actions, whose handlers refuse each selected item.
 */
@Component
public class ArchivedInterceptor implements HandlerInterceptor {

    /** The changing requests still let through: their handlers decide what an archived sheet gets. */
    private static final Set<String> LET_THROUGH = Set.of("/load", "/archive", "/claims/bulk-open",
            "/claims/bulk-settle",
            "/claims/bulk-reassign", "/claims/bulk-redate", "/claims/bulk-void");

    private final SheetService sheets;

    public ArchivedInterceptor(SheetService sheets) {
        this.sheets = sheets;
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
        if (variables == null) {
            return true;
        }
        String sheetId = variables.containsKey("sheetId") ? variables.get("sheetId") : variables.get("id");
        if (sheetId != null && sheets.isArchived(sheetId.trim())) {
            throw new SheetArchivedException(sheetId.trim());
        }
        return true;
    }
}
