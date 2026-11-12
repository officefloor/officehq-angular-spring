package net.officefloor.hq.app.history;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A screen showing the sheet as of a date sees it as it stood then and changes nothing: every change it
 * sends (a bulk action and a statement as of a date included) is refused before it reaches its handler and
 * writes nothing. Loading a sheet is not a change.
 */
@Configuration
public class AsOfGuard implements WebMvcConfigurer, HandlerInterceptor {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/api/sheets/*/**")
                .excludePathPatterns("/api/sheets/*/load");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String asOf = request.getHeader(AsOf.HEADER);
        if (asOf != null && !asOf.isBlank()) {
            throw new AsOfException();
        }
        return true;
    }
}
