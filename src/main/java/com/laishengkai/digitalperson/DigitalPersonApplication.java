package com.laishengkai.digitalperson;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/** Application entry point with persistence configured explicitly and conditionally. */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
public class DigitalPersonApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalPersonApplication.class, args);
    }
}
