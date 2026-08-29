package dev.telemetrylab.platform.gateway;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
@OpenAPIDefinition(
    info =
        @Info(
            title = "Industrial Telemetry Lab API",
            version = "1.0.0",
            description =
                "Local ingestion, inventory, query, inspection, reconciliation, and replay API",
            license = @License(name = "MIT", url = "https://opensource.org/license/mit")),
    servers = @Server(url = "http://localhost:8080", description = "Local Docker Compose gateway"))
@SecurityScheme(
    name = "localBearer",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    description = "Synthetic local-development token used by mutating endpoints")
class ApiDocumentationConfiguration {}
