package com.freezhub.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The bookkeeping around acknowledging a demo request (`FZ-217`).
 *
 * <p>What the message *says* is {@code DemoAcknowledgementMessageTest}'s job; this covers
 * what happens to the row, which is the part that decides whether anybody is left unanswered.
 */
class DemoRequestAcknowledgementTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String FROM = "camilo@freezehub.io";

    private DemoRequestRepository requests;
    private JavaMailSender mailSender;
    private DemoRequestAcknowledger acknowledger;
    private DemoRequest request;

    @BeforeEach
    void setUp() {
        request = new DemoRequest("Dana Okafor", "dana@northwind.test", "Northwind",
                "35", "tres hilos de Slack", "landing", NOW);

        requests = mock(DemoRequestRepository.class);
        when(requests.findPendingAcknowledgement(any(), anyInt(), any(Limit.class)))
                .thenReturn(List.of(request));

        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        acknowledger = new DemoRequestAcknowledger(requests, mailSender,
                new DemoAcknowledgementMessage(), FROM, "https://cal.example/freezehub", "");
    }

    @Test
    @DisplayName("marks the request acknowledged once it is sent")
    void marksAcknowledged() {
        int sent = acknowledger.acknowledgePending(NOW);

        assertThat(sent).isEqualTo(1);
        assertThat(request.getAcknowledgedAt()).isEqualTo(NOW);
        assertThat(request.getAcknowledgeError()).isNull();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("records a rejection and schedules a retry, rather than losing the person")
    void recordsFailure() {
        // This is every acknowledgement while SES is in the sandbox: the recipient is a
        // prospect, so their address is not a verified identity and SES refuses it.
        doThrow(new MailSendException("554 Message rejected"))
                .when(mailSender).send(any(MimeMessage.class));

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
        doThrow(new MailSendException("rejected")).when(mailSender).send(any(MimeMessage.class));

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
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
