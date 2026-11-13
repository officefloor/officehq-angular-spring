package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import net.officefloor.hq.app.AsOf;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * While a user's screen is set to an as-of date it shows the sheet as it stood then, and every change
 * it asks for (a statement as of a date included) is refused (409) before it reaches its controller,
 * so it writes nothing to the store or the audit. Loading the sheet changes nothing and is let through.
 */
@Component
public class AsOfGuard implements HandlerInterceptor {

    /** The sheet-scoped requests the guard watches. */
    public static final String PATTERN = "/api/sheets/{sheetId}/**";

    private static final Set<String> LET_THROUGH = Set.of("/api/sheets/{sheetId}/load");

    private final AntPathMatcher paths = new AntPathMatcher();

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
        String header = request.getHeader(AsOf.HEADER);
        if (header != null && !header.isBlank()) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return false;
        }
        return true;
    }
}
