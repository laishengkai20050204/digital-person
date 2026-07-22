package com.laishengkai.digitalperson;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Application entry point. Optional persistence is configured by project-owned beans. */
@SpringBootApplication
public class DigitalPersonApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalPersonApplication.class, args);
    }
}
