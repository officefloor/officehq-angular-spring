package net.officefloor.hq.app.role;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link ViewerGuard} in front of a sheet's API, except loading it, its bulk actions (on claims and on people)
 *  and generating its plans' claims, which a viewer takes with every item refused. */
@Configuration
public class ViewerGuardConfig implements WebMvcConfigurer {

    private final ViewerGuard guard;

    public ViewerGuardConfig(ViewerGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard)
                .addPathPatterns("/api/sheets/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/claims/bulk-*",
                        "/api/sheets/*/people/bulk-*", "/api/sheets/*/plans/generate");
    }
}
