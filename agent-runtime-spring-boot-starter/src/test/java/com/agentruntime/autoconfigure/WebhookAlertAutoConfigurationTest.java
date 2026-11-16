package com.agentruntime.autoconfigure;

import com.agentruntime.ops.OpsAlert;
import com.agentruntime.ops.WebhookOpsAlert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 告警出口的装配断言：不配 URL 不引入任何网络行为，配了才启用 webhook。 */
class WebhookAlertAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class));

    @Test
    void noWebhookBeanByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpsAlert.class)).isSameAs(OpsAlert.NOOP);
        });
    }

    @Test
    void webhookBeanCreatedWhenUrlConfigured() {
        runner.withPropertyValues("agent.runtime.ops.alert-webhook-url=http://127.0.0.1:9/hook")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpsAlert.class)).isInstanceOf(WebhookOpsAlert.class);
                });
    }
}
