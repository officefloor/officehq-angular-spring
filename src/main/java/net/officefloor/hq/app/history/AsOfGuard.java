package net.officefloor.hq.app.history;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses every change asked from a screen showing its sheet as of a date (one whose requests carry the
 * {@value #HEADER} header) before it reaches its controller, so it writes nothing. Reads go through, as does
 * loading the sheet; a bulk change goes through to its controller, which refuses each claim asked. */
@Configuration
public class AsOfGuard implements WebMvcConfigurer, HandlerInterceptor {

    /** The header a screen showing its sheet as of a date sends that date in (YYYY-MM-DD). */
    public static final String HEADER = "X-As-Of";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/locale", "/api/sheets/*/claims/bulk-*");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) return true;
        String asOf = request.getHeader(HEADER);
        if (asOf != null && !asOf.isBlank()) throw new AsOfRefusedException();
        return true;
    }
}
