package net.officefloor.hq.app.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses every change a viewer asks of a sheet before it reaches its controller, so it writes nothing.
 * Reads go through, as does loading the sheet; a bulk change goes through to {@code ClaimBulkService},
 * which refuses each claim a viewer asks of it, and generating plans goes through to {@code PlanService},
 * which makes nothing for a viewer, as does generating periods in {@code PeriodService}. */
@Configuration
public class ViewerGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final SettingsService settings;

    public ViewerGuard(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/locale", "/api/sheets/*/columns", "/api/sheets/*/claims/bulk-*",
                        "/api/sheets/*/people/bulk-grade", "/api/sheets/*/people/bulk-retire", "/api/sheets/*/plans/generate",
                        "/api/sheets/*/periods/generate");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) return true;
        String user = request.getHeader("X-User");
        String sheetId = sheetId(request);
        if (user == null || user.isBlank() || sheetId == null) return true;
        if (settings.roleOf(sheetId.trim(), user.trim()) == Role.VIEWER) throw new ViewerRefusedException();
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String sheetId(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map)) return null;
        Object id = map.containsKey("sheetId") ? map.get("sheetId") : map.get("id");
        return id == null ? null : id.toString();
    }
}
