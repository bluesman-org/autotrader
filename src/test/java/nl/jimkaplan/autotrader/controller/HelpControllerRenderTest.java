package nl.jimkaplan.autotrader.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The help page is static, but a broken expression or a missing section should fail here rather
 * than in the browser. The assertions also pin the simple explanations and examples that make the
 * page useful to the operator.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = HelpController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
class HelpControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pageRendersTheOperatorGuide() throws Exception {
        mockMvc.perform(get("/ui/help"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reading a backtest")))
                .andExpect(content().string(containsString("id=\"backtest\"")))
                .andExpect(content().string(containsString("Train — first 70%")))
                .andExpect(content().string(containsString("Validate — last 30%")))
                .andExpect(content().string(containsString("Max drawdown")))
                .andExpect(content().string(containsString("Profit factor")))
                .andExpect(content().string(containsString("Possible spam")))
                .andExpect(content().string(containsString("brief stream outage")))
                .andExpect(content().string(containsString("cannot be resolved")))
                .andExpect(content().string(containsString("Closing and history")))
                .andExpect(content().string(containsString("id=\"glossary\"")))
                .andExpect(content().string(containsString("href=\"/ui/help\"")));
    }

    @Test
    void pageHasTheWorkedExamples() throws Exception {
        mockMvc.perform(get("/ui/help"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("One round trip")))
                .andExpect(content().string(containsString("Max drawdown.")))
                .andExpect(content().string(containsString("Profit factor.")))
                .andExpect(content().string(containsString("Win rate.")))
                .andExpect(content().string(containsString("€6")))
                .andExpect(content().string(containsString("Mark as safe to Chrome")));
    }
}
