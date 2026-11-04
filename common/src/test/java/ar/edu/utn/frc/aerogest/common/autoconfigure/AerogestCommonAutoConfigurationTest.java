package ar.edu.utn.frc.aerogest.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.utn.frc.aerogest.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** El filtro se registra solo en aplicaciones servlet (los microservicios), no en el gateway reactivo. */
class AerogestCommonAutoConfigurationTest {

    private final AutoConfigurations config = AutoConfigurations.of(AerogestCommonAutoConfiguration.class);

    @Test
    void registraElFiltroEnUnaAplicacionServlet() {
        new WebApplicationContextRunner().withConfiguration(config)
                .run(context -> assertThat(context).hasSingleBean(CorrelationIdFilter.class));
    }

    @Test
    void noLoRegistraEnUnaAplicacionReactivaNiFueraDeLaWeb() {
        new ReactiveWebApplicationContextRunner().withConfiguration(config)
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
        new ApplicationContextRunner().withConfiguration(config)
                .run(context -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
    }

    @Test
    void respetaUnFiltroDeclaradoPorElServicio() {
        CorrelationIdFilter propio = new CorrelationIdFilter();
        new WebApplicationContextRunner().withConfiguration(config)
                .withBean(CorrelationIdFilter.class, () -> propio)
                .run(context -> assertThat(context.getBean(CorrelationIdFilter.class)).isSameAs(propio));
    }
}
