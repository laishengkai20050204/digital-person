package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.infrastructure.context.StateEvaluationContextConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationGatewayBeanNameTest {

    @Test
    void mysqlGatewayDoesNotReuseFallbackGatewayBeanName() {
        Method fallback = gatewayBeanMethod(StateEvaluationContextConfiguration.class);
        Method mysql = gatewayBeanMethod(MySqlPersonPersistenceConfiguration.class);

        assertThat(beanName(mysql))
                .as("real MySQL gateway must not collide with the no-op fallback bean")
                .isNotEqualTo(beanName(fallback));
        assertThat(beanName(mysql)).isEqualTo("summaryAwareRecentConversationGateway");
        assertThat(beanName(fallback)).isEqualTo("recentConversationGateway");
    }

    @Test
    void mysqlRepositoryIsTheOnlyBeanExposingEachConversationStoreCapability() {
        assertThat(beanMethodsAssignableTo(RecentConversationStore.class))
                .extracting(Method::getName)
                .containsExactly("jdbcRecentConversationRepository");
        assertThat(beanMethodsAssignableTo(ConversationSummaryStore.class))
                .extracting(Method::getName)
                .containsExactly("jdbcRecentConversationRepository");
    }

    private static Method gatewayBeanMethod(Class<?> configurationClass) {
        return Arrays.stream(configurationClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> method.getReturnType().equals(RecentConversationGateway.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "configuration has no explicit RecentConversationGateway bean: "
                                + configurationClass.getName()
                ));
    }

    private static List<Method> beanMethodsAssignableTo(Class<?> capability) {
        return Arrays.stream(MySqlPersonPersistenceConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> capability.isAssignableFrom(method.getReturnType()))
                .toList();
    }

    private static String beanName(Method method) {
        Bean bean = method.getAnnotation(Bean.class);
        if (bean.name().length > 0 && !bean.name()[0].isBlank()) {
            return bean.name()[0];
        }
        if (bean.value().length > 0 && !bean.value()[0].isBlank()) {
            return bean.value()[0];
        }
        return method.getName();
    }
}
