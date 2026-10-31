package net.officefloor.hq.app.sheet;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link ArchiveGuard} in front of a sheet's API, except loading it and its bulk actions. */
@Configuration
public class ArchiveGuardConfig implements WebMvcConfigurer {

    private final ArchiveGuard guard;

    public ArchiveGuardConfig(ArchiveGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard)
                .addPathPatterns("/api/sheets/**")
                .excludePathPatterns("/api/sheets/*/load", "/api/sheets/*/claims/bulk-*");
    }
}
