package com.laishengkai.digitalperson.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers external configuration for protected person HTTP adapters. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        PersonApiProperties.class,
        OpenAiCompatibilityProperties.class
})
public class PersonApiConfiguration {
}
