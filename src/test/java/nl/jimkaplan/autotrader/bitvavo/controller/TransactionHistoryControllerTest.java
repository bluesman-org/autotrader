package nl.jimkaplan.autotrader.bitvavo.controller;

import nl.jimkaplan.autotrader.bitvavo.model.GetAccountHistoryResponse;
import nl.jimkaplan.autotrader.bitvavo.service.TransactionHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryControllerTest {

    @Mock
    private TransactionHistoryService transactionHistoryService;

    @InjectMocks
    private TransactionHistoryController transactionHistoryController;

    private static final String BOT_ID = "abc123";

    @Test
    void getTransactions_withAllParameters_returnsOkResponse() {
        // Arrange
        GetAccountHistoryResponse historyResponse = new GetAccountHistoryResponse();
        when(transactionHistoryService.getTransactions(BOT_ID, 1706100650751L, 1706100650752L, 2, 5, "buy"))
                .thenReturn(historyResponse);

        // Act
        ResponseEntity<GetAccountHistoryResponse> response = transactionHistoryController
                .getTransactions(BOT_ID, 1706100650751L, 1706100650752L, 2, 5, "buy");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(historyResponse, response.getBody());
        verify(transactionHistoryService).getTransactions(BOT_ID, 1706100650751L, 1706100650752L, 2, 5, "buy");
    }

    @Test
    void getTransactions_withoutOptionalParameters_returnsOkResponse() {
        // Arrange
        GetAccountHistoryResponse historyResponse = new GetAccountHistoryResponse();
        when(transactionHistoryService.getTransactions(BOT_ID, null, null, null, null, null))
                .thenReturn(historyResponse);

        // Act
        ResponseEntity<GetAccountHistoryResponse> response = transactionHistoryController
                .getTransactions(BOT_ID, null, null, null, null, null);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(transactionHistoryService).getTransactions(BOT_ID, null, null, null, null, null);
    }
}
