package nl.jimkaplan.autotrader.config;

import nl.jimkaplan.autotrader.repository.MarketRepository;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MarketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketInitializerTest {

    @Mock
    private MarketService marketService;
    @Mock
    private MarketRepository marketRepository;
    @Mock
    private BitvavoCredentialsService credentialsService;

    @InjectMocks
    private MarketInitializer marketInitializer;

    @Test
    void seedsAnEmptyCatalogueWhenTheAccountKeyIsConfigured() {
        when(marketRepository.count()).thenReturn(0L);
        when(credentialsService.configured()).thenReturn(true);
        when(marketService.discover()).thenReturn(new MarketService.Discovery(11, 11, 0, 0));

        marketInitializer.run(new DefaultApplicationArguments());

        verify(marketService).discover();
    }

    @Test
    void leavesAPopulatedCatalogueAlone() {
        when(marketRepository.count()).thenReturn(11L);

        marketInitializer.run(new DefaultApplicationArguments());

        verify(marketService, never()).discover();
    }

    @Test
    void doesNothingWithoutAnAccountKey() {
        when(marketRepository.count()).thenReturn(0L);
        when(credentialsService.configured()).thenReturn(false);

        marketInitializer.run(new DefaultApplicationArguments());

        verify(marketService, never()).discover();
    }

    @Test
    void aFailedDiscoveryDoesNotStopTheBoot() {
        when(marketRepository.count()).thenReturn(0L);
        when(credentialsService.configured()).thenReturn(true);
        when(marketService.discover()).thenThrow(new IllegalStateException("Bitvavo is down"));

        assertDoesNotThrow(() -> marketInitializer.run(new DefaultApplicationArguments()));
    }
}
