package ar.edu.utn.frc.aerogest.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** El correlation id se respeta si viene, se genera si falta y está en el MDC solo durante la solicitud. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void respetaElIdRecibidoYLoDevuelveEnLaRespuesta() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                duringRequest.set(MDC.get(CorrelationId.MDC_KEY));
            }
        }));

        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123");
        assertThat(duringRequest.get()).isEqualTo("abc-123");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void generaUnIdSiNoVieneOEsDemasiadoLargo() throws Exception {
        MockHttpServletRequest sinId = new MockHttpServletRequest();
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        filter.doFilter(sinId, response1, new MockFilterChain());

        MockHttpServletRequest largo = new MockHttpServletRequest();
        largo.addHeader(CorrelationId.HEADER, "x".repeat(101));
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        filter.doFilter(largo, response2, new MockFilterChain());

        assertThat(response1.getHeader(CorrelationId.HEADER)).hasSize(36);
        assertThat(response2.getHeader(CorrelationId.HEADER)).hasSize(36);
    }
}
