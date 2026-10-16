package nl.jimkaplan.autotrader;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(
        info = @Info(
                title = "AutoTrader API",
                version = "1.0",
                description = "API for automating trading strategies"
        ),
        // Every documented operation lives under /api/**, which takes the installation's account
        // over HTTP Basic.
        security = @SecurityRequirement(name = "basicAuth")
)
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic",
        description = "The installation's account: HTTP Basic, the same credentials the sign-in page takes.")
@SpringBootApplication
public class AutotraderApplication {

    private static final Logger log = LoggerFactory.getLogger(AutotraderApplication.class);

    public static void main(String[] args) {
        log.info("Starting application");
        SpringApplication.run(AutotraderApplication.class, args);
    }

}
