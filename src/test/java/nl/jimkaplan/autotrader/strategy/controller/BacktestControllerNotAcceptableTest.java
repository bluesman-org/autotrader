package nl.jimkaplan.autotrader.strategy.controller;

import nl.jimkaplan.autotrader.strategy.service.BacktestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the wire behaviour of a rejected {@code Accept}: content negotiation raises
 * {@code HttpMediaTypeNotAcceptableException}, and the advice must answer with an empty 406 — the
 * one response that can always be written — instead of a JSON body no converter can render (the
 * catch-all's chain, which degraded to a 500). The empty body leaves the {@code Accept} response
 * header as the only carrier of the producible types.
 *
 * <p>The property overrides stand in for the environment variables the application.yml
 * placeholders expect, which a test run does not have.</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = BacktestController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk="
})
class BacktestControllerNotAcceptableTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BacktestService backtestService;

    @Test
    void anAcceptHeaderNoProducibleTypeMatchesAnswersAnEmpty406() throws Exception {
        when(backtestService.recent(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/backtests").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
    }
}
