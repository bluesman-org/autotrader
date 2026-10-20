package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.model.document.BotConfiguration;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.BotConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one-time copy of the key that used to live inside a bot configuration: it runs only while
 * nothing is stored, picks the first active configuration with a key, and never stops the
 * application.
 */
@ExtendWith(MockitoExtension.class)
class BitvavoCredentialsInitializerTest {

    @Mock
    private BitvavoCredentialsService credentialsService;
    @Mock
    private BotConfigurationService botConfigurationService;

    private BitvavoCredentialsInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new BitvavoCredentialsInitializer(credentialsService, botConfigurationService);
    }

    @Test
    void run_copiesTheFirstActiveConfigurationWithAKey() {
        when(credentialsService.configured()).thenReturn(false);
        when(botConfigurationService.getAllBotConfigurations()).thenReturn(List.of(
                bot("bot-b", "key-b"), bot("bot-a", "key-a"), withoutKey("bot-c")));

        initializer.run(null);

        verify(credentialsService).save("key-a", "secret", 7L);
    }

    @Test
    void run_isANoOpWhenAKeyIsAlreadyStored() {
        when(credentialsService.configured()).thenReturn(true);

        initializer.run(null);

        verifyNoInteractions(botConfigurationService);
    }

    @Test
    void run_copiesNothingWhenNoConfigurationCarriesAKey() {
        when(credentialsService.configured()).thenReturn(false);
        when(botConfigurationService.getAllBotConfigurations()).thenReturn(List.of(withoutKey("bot-c")));

        initializer.run(null);

        verify(credentialsService, never()).save(any(), any(), any());
    }

    @Test
    void run_survivesAFailureAndLeavesTheKeyToTheSettingsPage() {
        when(credentialsService.configured()).thenReturn(false);
        when(botConfigurationService.getAllBotConfigurations())
                .thenReturn(List.of(bot("bot-1", "key-bot-1")));
        when(credentialsService.save(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> initializer.run(null));
    }

    private static BotConfiguration bot(String botId, String apiKey) {
        return BotConfiguration.builder()
                .botId(botId)
                .operatorId(7L)
                .apiKey(apiKey)
                .apiSecret("secret")
                .build();
    }

    private static BotConfiguration withoutKey(String botId) {
        return BotConfiguration.builder().botId(botId).build();
    }
}
