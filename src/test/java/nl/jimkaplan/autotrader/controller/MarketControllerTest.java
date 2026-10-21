package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.model.document.Market;
import nl.jimkaplan.autotrader.service.MarketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketControllerTest {

    @Mock
    private MarketService marketService;

    @InjectMocks
    private MarketController marketController;

    @Test
    void allReturnsTheCatalogue() {
        when(marketService.all()).thenReturn(List.of(market("BTC-EUR")));

        ResponseEntity<List<Market>> response = marketController.all();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void addReturnsCreatedWithTheStoredPair() {
        MarketRequest request = new MarketRequest();
        request.setMarket("ETH-EUR");
        Market stored = market("ETH-EUR");
        when(marketService.add("ETH-EUR")).thenReturn(stored);

        ResponseEntity<Market> response = marketController.add(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(stored, response.getBody());
    }

    @Test
    void enableFlipsThePairOn() {
        Market stored = market("ETH-EUR");
        when(marketService.setEnabled("ETH-EUR", true)).thenReturn(stored);

        ResponseEntity<Market> response = marketController.enable("ETH-EUR");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stored, response.getBody());
    }

    @Test
    void disableFlipsThePairOff() {
        Market stored = market("ETH-EUR");
        when(marketService.setEnabled("ETH-EUR", false)).thenReturn(stored);

        ResponseEntity<Market> response = marketController.disable("ETH-EUR");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(stored, response.getBody());
    }

    @Test
    void discoverReturnsWhatWasFound() {
        MarketService.Discovery discovery = new MarketService.Discovery(11, 11, 0, 0);
        when(marketService.discover()).thenReturn(discovery);

        ResponseEntity<MarketService.Discovery> response = marketController.discover();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(discovery, response.getBody());
        verify(marketService).discover();
    }

    private static Market market(String symbol) {
        return Market.builder()
                .market(symbol)
                .base("ETH")
                .quote("EUR")
                .enabled(true)
                .source(Market.SOURCE_DISCOVERED)
                .build();
    }
}
