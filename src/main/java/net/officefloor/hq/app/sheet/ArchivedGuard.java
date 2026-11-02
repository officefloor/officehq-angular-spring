package net.officefloor.hq.app.sheet;

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

/** Refuses every change asked of an archived sheet (409), before it writes anything; only the
 *  requests marked {@link ArchivedAllowed} go through. */
@Configuration
public class ArchivedGuard implements HandlerInterceptor, WebMvcConfigurer {

    private final SheetService sheets;

    public ArchivedGuard(SheetService sheets) {
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || !(handler instanceof HandlerMethod method)
                || method.hasMethodAnnotation(ArchivedAllowed.class)) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (sheetId != null && sheets.archived(sheetId.trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived: it may not be changed");
        }
        return true;
    }
}
