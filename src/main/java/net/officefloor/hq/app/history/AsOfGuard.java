package net.officefloor.hq.app.history;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The as-of date a user's screen shows the sheet as of, sent in the {@link #HEADER} header (yyyy-mm-dd)
 * on every request while it is set. Every change asked for while it is set is refused: any request
 * other than a read, or the loading of the sheet, is turned away before it reaches its handler, so it
 * writes nothing and audits nothing.
 */
@Configuration
public class AsOfGuard implements WebMvcConfigurer, HandlerInterceptor {

    public static final String HEADER = "X-As-Of";

    /** The as-of date from the header's value; null when none is set. */
    public static LocalDate date(String header) {
        String text = header == null ? "" : header.trim();
        try {
            return text.isEmpty() ? null : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an as-of date: " + text);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**").excludePathPatterns("/api/sheets/*/load");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        if (date(request.getHeader(HEADER)) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The sheet is shown as of a date: nothing may change until the as-of date is cleared.");
        }
        return true;
    }
}
