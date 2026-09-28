package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * What "notified" means when there is more than one channel (`FZ-214`).
 *
 * <p>Unit, not {@code @SpringBootTest}: the question here is the loop's decision, and the
 * loop is the only thing that decides it. {@code DemoRequestNotificationTest} already covers
 * the persistence and the retry schedule against a real database, and duplicating that here
 * would test Spring twice and the decision once.
 */
class DemoRequestChannelsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    /** A channel that records what it was asked to send, and optionally refuses. */
    private static final class FakeChannel implements DemoRequestChannel {
        private final String name;
        private final boolean configured;
        private final boolean fails;
        private final List<DemoRequest> announced = new ArrayList<>();

        FakeChannel(String name, boolean configured, boolean fails) {
            this.name = name;
            this.configured = configured;
            this.fails = fails;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void announce(DemoRequest request) {
            if (fails) {
                throw new DemoRequestChannelException("refused");
            }
            announced.add(request);
        }
    }

    private DemoRequest request;

    @BeforeEach
    void setUp() {
        request = new DemoRequest("Dana Okafor", "dana@northwind.test", "Northwind",
                "35", "three Slack threads", "landing", NOW);
    }

    private DemoRequest announceWith(DemoRequestChannel... channels) {
        // The repository is only asked for the batch; the decision under test needs no
        // database, so it is mocked to hand back the one request.
        DemoRequestRepository repository = mock(DemoRequestRepository.class);
        when(repository.findPendingNotification(any(), anyInt(), any(Limit.class)))
                .thenReturn(List.of(request));

        new DemoRequestNotifier(repository, List.of(channels)).notifyPending(NOW);
        return request;
    }

    @Test
    @DisplayName("every channel succeeding is notified, with no error recorded")
    void allSucceed() {
        FakeChannel slack = new FakeChannel("Slack", true, false);
        FakeChannel email = new FakeChannel("email", true, false);

        DemoRequest result = announceWith(slack, email);

        assertThat(result.getNotifiedAt()).isEqualTo(NOW);
        assertThat(result.getNotifyError()).isNull();
        assertThat(slack.announced).containsExactly(request);
        assertThat(email.announced).containsExactly(request);
    }

    @Test
    @DisplayName("one of two succeeding is still notified, and says which failed")
    void partialSuccess() {
        // The whole point: retrying would send the channel that worked the same lead again.
        // A duplicate in Slack is noise; the purpose was met on the first pass.
        FakeChannel slack = new FakeChannel("Slack", true, false);
        FakeChannel email = new FakeChannel("email", true, true);

        DemoRequest result = announceWith(slack, email);

        assertThat(result.getNotifiedAt()).isEqualTo(NOW);
        assertThat(result.getNotifyError()).contains("email").contains("refused");
        // Not retried — a notified request is not picked up again.
        assertThat(result.getNotifyAttempts()).isZero();
    }

    @Test
    @DisplayName("no channel succeeding is not notified, and is retried")
    void allFail() {
        FakeChannel slack = new FakeChannel("Slack", true, true);
        FakeChannel email = new FakeChannel("email", true, true);

        DemoRequest result = announceWith(slack, email);

        assertThat(result.getNotifiedAt()).isNull();
        assertThat(result.getNotifyAttempts()).isEqualTo(1);
        assertThat(result.getNotifyError()).contains("Slack").contains("email");
    }

    @Test
    @DisplayName("an unconfigured channel is not asked, and does not count as a failure")
    void unconfiguredIsSkipped() {
        FakeChannel slack = new FakeChannel("Slack", true, false);
        FakeChannel email = new FakeChannel("email", false, true);

        DemoRequest result = announceWith(slack, email);

        assertThat(result.getNotifiedAt()).isEqualTo(NOW);
        assertThat(result.getNotifyError()).isNull();
        assertThat(email.announced).isEmpty();
    }

    @Test
    @DisplayName("with nothing configured the request is left alone, not failed")
    void nothingConfigured() {
        // The lead is the row, not the message. Recording a failure here would start a
        // retry schedule for a destination that does not exist.
        FakeChannel slack = new FakeChannel("Slack", false, false);

        DemoRequest result = announceWith(slack);

        assertThat(result.getNotifiedAt()).isNull();
        assertThat(result.getNotifyAttempts()).isZero();
        assertThat(result.getNotifyError()).isNull();
    }
}
