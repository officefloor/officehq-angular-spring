package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * On an archived sheet everything stays visible and every change is refused (409) before it
 * reaches its controller, so it writes nothing to the store or the audit. Loading the sheet changes
 * nothing and is let through; so is archiving, which refuses a sheet archived already, and the bulk
 * actions, which refuse every selected item (see the bulk services).
 */
@Component
public class ArchivedGuard implements HandlerInterceptor {

    /** The sheet-scoped requests the guard watches. */
    public static final String PATTERN = "/api/sheets/{sheetId}/**";

    private static final Set<String> LET_THROUGH = Set.of(
            "/api/sheets/{sheetId}/load",
            "/api/sheets/{sheetId}/archive",
            "/api/sheets/{sheetId}/claims/open",
            "/api/sheets/{sheetId}/claims/settle");

    private final AntPathMatcher paths = new AntPathMatcher();
    private final SheetService sheets;

    public ArchivedGuard(SheetService sheets) {
        this.sheets = sheets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!paths.match(PATTERN, path) || LET_THROUGH.stream().anyMatch(p -> paths.match(p, path))) {
            return true;
        }
        String sheetId = URLDecoder.decode(paths.extractUriTemplateVariables(PATTERN, path).get("sheetId"),
                StandardCharsets.UTF_8).trim();
        if (sheets.isArchived(sheetId)) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return false;
        }
        return true;
    }
}
