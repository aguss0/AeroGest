package ar.edu.utn.frc.aerogest.common.autoconfigure;

import ar.edu.utn.frc.aerogest.common.web.CorrelationIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Se activa sola al agregar la dependencia (META-INF/spring/...AutoConfiguration.imports).
 * Solo en aplicaciones servlet: el gateway es reactivo y tiene su propio filtro de correlation id.
 * Un servicio puede reemplazar el filtro declarando su propio bean.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AerogestCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
