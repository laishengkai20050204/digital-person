package com.laishengkai.digitalperson.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers only external configuration for the protected person HTTP adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonApiProperties.class)
public class PersonApiConfiguration {
}
