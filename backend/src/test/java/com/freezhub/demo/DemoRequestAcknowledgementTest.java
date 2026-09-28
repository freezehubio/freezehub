package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Acknowledging the person who asked (`FZ-217`).
 *
 * <p>Unit rather than {@code @SpringBootTest}: what matters here is the message and the
 * failure bookkeeping, and neither needs a database. {@code DemoRequestNotificationTest}
 * already covers persistence and the retry schedule against a real PostgreSQL.
 */
class DemoRequestAcknowledgementTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String FROM = "hola@freezehub.io";

    private DemoRequestRepository requests;
    private JavaMailSender mailSender;
    private DemoRequestAcknowledger acknowledger;
    private DemoRequest request;

    @BeforeEach
    void setUp() {
        request = new DemoRequest("Dana Okafor", "dana@northwind.test", "Northwind",
                "35", "Freeze windows live in three Slack threads.", "landing", NOW);

        requests = mock(DemoRequestRepository.class);
        when(requests.findPendingAcknowledgement(any(), anyInt(), any(Limit.class)))
                .thenReturn(List.of(request));

        mailSender = mock(JavaMailSender.class);
        acknowledger = new DemoRequestAcknowledger(requests, mailSender, FROM);
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("writes to the person who asked, from an address that is answered")
    void addressing() {
        acknowledger.acknowledgePending(NOW);
        SimpleMailMessage message = sentMessage();

        assertThat(message.getTo()).containsExactly("dana@northwind.test");
        assertThat(message.getFrom()).isEqualTo(FROM);
        // Not the send-only notification sender: replying to this has to reach somebody.
        assertThat(message.getReplyTo()).isEqualTo(FROM);
        assertThat(message.getSubject()).contains("FreezeHub");
    }

    @Test
    @DisplayName("greets by first name, not by the whole database field")
    void greeting() {
        acknowledger.acknowledgePending(NOW);

        assertThat(sentMessage().getText()).startsWith("Hi Dana,");
    }

    @Test
    @DisplayName("quotes their own words back, so they can see it arrived intact")
    void quotesTheMessage() {
        acknowledger.acknowledgePending(NOW);

        assertThat(sentMessage().getText()).contains("three Slack threads");
    }

    @Test
    @DisplayName("promises no delivery time the product cannot keep")
    void promisesNothingUntrue() {
        // A human answers these. "Within one business day" written here would be a
        // commitment nobody agreed to.
        acknowledger.acknowledgePending(NOW);
        String text = sentMessage().getText();

        assertThat(text).doesNotContainIgnoringCase("business day");
        assertThat(text).doesNotContainIgnoringCase("24 hours");
        assertThat(text).contains("will get back to you");
    }

    @Test
    @DisplayName("marks the request acknowledged once it is sent")
    void marksAcknowledged() {
        int sent = acknowledger.acknowledgePending(NOW);

        assertThat(sent).isEqualTo(1);
        assertThat(request.getAcknowledgedAt()).isEqualTo(NOW);
        assertThat(request.getAcknowledgeError()).isNull();
    }

    @Test
    @DisplayName("records a rejection and schedules a retry, rather than losing the person")
    void recordsFailure() {
        // This is every acknowledgement while SES is in the sandbox: the recipient is a
        // prospect, so their address is not a verified identity and SES refuses it.
        doThrow(new MailSendException("554 Message rejected")).when(mailSender).send(any(SimpleMailMessage.class));

        int sent = acknowledger.acknowledgePending(NOW);

        assertThat(sent).isZero();
        assertThat(request.getAcknowledgedAt()).isNull();
        assertThat(request.getAcknowledgeAttempts()).isEqualTo(1);
        assertThat(request.getAcknowledgeError()).contains("MailSendException");
        // The server's text can name the credential it rejected; only the class name is kept.
        assertThat(request.getAcknowledgeError()).doesNotContain("554");
    }

    @Test
    @DisplayName("a failure to acknowledge leaves the internal announcement alone")
    void independentOfTheInternalAnnouncement() {
        // Separate promises to separate people. One failing must not mark the other done,
        // nor undo it.
        doThrow(new MailSendException("rejected")).when(mailSender).send(any(SimpleMailMessage.class));

        acknowledger.acknowledgePending(NOW);

        assertThat(request.getNotifiedAt()).isNull();
        assertThat(request.getNotifyAttempts()).isZero();
        assertThat(request.getNotifyError()).isNull();
    }

    @Test
    @DisplayName("sends nothing when nothing is waiting")
    void nothingPending() {
        when(requests.findPendingAcknowledgement(any(), anyInt(), any(Limit.class)))
                .thenReturn(List.of());

        assertThat(acknowledger.acknowledgePending(NOW)).isZero();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
