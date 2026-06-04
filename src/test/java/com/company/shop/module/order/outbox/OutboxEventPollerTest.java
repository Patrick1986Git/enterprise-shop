package com.company.shop.module.order.outbox;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxEventPollerTest {

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    private OutboxProcessingProperties properties;
    private OutboxEventPoller poller;

    @BeforeEach
    void setUp() {
        properties = new OutboxProcessingProperties();
        poller = new OutboxEventPoller(outboxEventProcessor, properties);
    }

    @Test
    void processPendingOutboxEvents_shouldNotProcessWhenDisabled() {
        properties.setEnabled(false);

        poller.processPendingOutboxEvents();

        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void processPendingOutboxEvents_shouldProcessWithConfiguredBatchSizeWhenEnabled() {
        properties.setEnabled(true);
        properties.setBatchSize(7);
        when(outboxEventProcessor.processPendingBatch(7)).thenReturn(new OutboxEventProcessingResult(1, 0));

        poller.processPendingOutboxEvents();

        verify(outboxEventProcessor).processPendingBatch(7);
    }

    @Test
    void processPendingOutboxEvents_shouldHandleEmptyResultWithoutError() {
        properties.setEnabled(true);
        when(outboxEventProcessor.processPendingBatch(properties.batchSize()))
                .thenReturn(new OutboxEventProcessingResult(0, 0));

        poller.processPendingOutboxEvents();

        verify(outboxEventProcessor).processPendingBatch(properties.batchSize());
    }
}
