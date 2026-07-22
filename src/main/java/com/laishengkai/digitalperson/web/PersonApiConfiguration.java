package com.laishengkai.digitalperson.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Registers external configuration for the optional protected person API. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonApiProperties.class)
public class PersonApiConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock personApiClock() {
        return Clock.systemUTC();
    }
}
