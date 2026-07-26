package com.laishengkai.digitalperson.infrastructure.spring;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registers capability beans after configuration parsing has exposed every user bean definition.
 * This avoids the configuration-order limitation of method-level {@code ConditionalOnBean}.
 */
public final class LateConditionalBeanRegistrar implements BeanFactoryPostProcessor {

    private final Consumer<DefaultListableBeanFactory> registration;

    public LateConditionalBeanRegistrar(
            Consumer<DefaultListableBeanFactory> registration
    ) {
        this.registration = Objects.requireNonNull(
                registration,
                "registration cannot be null"
        );
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!(beanFactory instanceof DefaultListableBeanFactory defaultBeanFactory)) {
            throw new IllegalStateException(
                    "LateConditionalBeanRegistrar requires DefaultListableBeanFactory"
            );
        }
        registration.accept(defaultBeanFactory);
    }

    public static boolean hasBean(
            DefaultListableBeanFactory beanFactory,
            Class<?> beanType
    ) {
        return beanFactory.getBeanNamesForType(beanType, true, false).length > 0;
    }

    public static boolean hasBean(
            DefaultListableBeanFactory beanFactory,
            String beanName
    ) {
        return beanFactory.containsBeanDefinition(beanName)
                || beanFactory.containsSingleton(beanName);
    }

    public static <T> void registerIfPossible(
            DefaultListableBeanFactory beanFactory,
            String beanName,
            Class<T> beanType,
            Supplier<T> instanceSupplier,
            Class<?>... requiredTypes
    ) {
        Objects.requireNonNull(beanFactory, "beanFactory cannot be null");
        Objects.requireNonNull(beanName, "beanName cannot be null");
        Objects.requireNonNull(beanType, "beanType cannot be null");
        Objects.requireNonNull(instanceSupplier, "instanceSupplier cannot be null");
        Objects.requireNonNull(requiredTypes, "requiredTypes cannot be null");

        if (hasBean(beanFactory, beanType)) {
            return;
        }
        for (Class<?> requiredType : requiredTypes) {
            if (!hasBean(beanFactory, requiredType)) {
                return;
            }
        }

        RootBeanDefinition definition = new RootBeanDefinition(beanType);
        definition.setInstanceSupplier(instanceSupplier);
        beanFactory.registerBeanDefinition(beanName, definition);
    }
}
