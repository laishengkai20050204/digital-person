package com.laishengkai.digitalperson.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers external configuration for the optional protected person API. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonApiProperties.class)
public class PersonApiConfiguration {
}
