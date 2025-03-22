package nl.jimkaplan.autotrader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AutotraderApplication {

    private static final Logger log = LoggerFactory.getLogger(AutotraderApplication.class);

    public static void main(String[] args) {
        log.info("Starting Autotrader application");
        SpringApplication.run(AutotraderApplication.class, args);
        log.info("Autotrader application started successfully");
    }

}
