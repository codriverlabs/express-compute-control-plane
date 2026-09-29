package ai.codriverlabs.ecp.tenant.resource;

import ai.codriverlabs.ecp.tenant.TenantNaming;
import ai.codriverlabs.ecp.tenant.model.TenantProgress;
import ai.codriverlabs.ecp.tenant.resource.TenantStreamResource.ProgressMessage;
import ai.codriverlabs.ecp.tenant.service.DryRunProvisioningService;
import ai.codriverlabs.ecp.tenant.service.TenantProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantStreamResourceTest {

    @Mock SqsClient sqs;
    @Mock TenantProvisioningService provisioningService;
    @Mock DryRunProvisioningService dryRunService;

    TenantStreamResource resource;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        resource = new TenantStreamResource();
        setField("sqs", sqs);
        setField("provisioningService", provisioningService);
        setField("dryRunService", dryRunService);
        setField("objectMapper", objectMapper);
    }

    // ── Validation tests ─────────────────────────────────────────────────────

    @Test
    void validateAndPersist_acceptsForwardProgress() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Booting", 10, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "provisioning", "Installing CNI", 50);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNotNull(result);
        assertEquals("provisioning", result.state());
        assertEquals("Installing CNI", result.phase());
        assertEquals(50, result.progress());
        verify(provisioningService).updateProgressFromSqs("tenant-1", "provisioning", "Installing CNI", 50);
    }

    @Test
    void validateAndPersist_rejectsBackwardProgress() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Installing CNI", 50, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "provisioning", "Booting", 10);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNull(result);
        verify(provisioningService, never()).updateProgressFromSqs(any(), any(), any(), anyInt());
    }

    @Test
    void validateAndPersist_rejectsSameProgress() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Installing CNI", 50, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "provisioning", "Installing CNI again", 50);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNull(result);
        verify(provisioningService, never()).updateProgressFromSqs(any(), any(), any(), anyInt());
    }

    @Test
    void validateAndPersist_rejectsTenantMismatch() throws Exception {
        ProgressMessage msg = new ProgressMessage("other-tenant", "provisioning", "Booting", 10);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNull(result);
        verify(provisioningService, never()).updateProgressFromSqs(any(), any(), any(), anyInt());
    }

    @Test
    void validateAndPersist_rejectsInvalidState() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Booting", 10, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "hacked", "Arbitrary state", 50);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNull(result);
        verify(provisioningService, never()).updateProgressFromSqs(any(), any(), any(), anyInt());
    }

    @Test
    void validateAndPersist_acceptsK3sStartingState() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "k3s configuration written", 18, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "k3s-starting", "Starting k3s server", 20);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNotNull(result, "k3s-starting must be accepted -- it is a real progress "
            + "signal from setup-k3s-xpress.sh, not an invalid/unknown state");
        assertEquals("k3s-starting", result.state());
        assertEquals(20, result.progress());
        verify(provisioningService).updateProgressFromSqs("tenant-1", "k3s-starting", "Starting k3s server", 20);
    }

    @Test
    void validateAndPersist_acceptsK3sReadyState() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("k3s-starting", "Starting k3s server", 20, null, 0, null, null));

        ProgressMessage msg = new ProgressMessage("tenant-1", "k3s-ready", "k3s server running", 35);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNotNull(result, "k3s-ready must be accepted -- it means the k3s server "
            + "process started, not that the cluster is fully provisioned");
        assertEquals("k3s-ready", result.state());
        assertEquals(35, result.progress());
        verify(provisioningService).updateProgressFromSqs("tenant-1", "k3s-ready", "k3s server running", 35);
    }

    @Test
    void validateAndPersist_k3sReadyIsNotTreatedAsTerminal() throws Exception {
        // "k3s-ready" (35%) means the k3s server process started -- it must NOT be treated
        // as the terminal cluster-ready signal. Only the generic "ready" state (100%, sent
        // separately by report_ready() at the end of the boot script) is terminal. Mistakenly
        // treating k3s-ready as terminal would delete the progress queue at 35% and drop all
        // remaining VPC CNI / add-on / Karpenter progress reporting.
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/ecp-tenant-t1-progress.fifo";
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenReturn(GetQueueUrlResponse.builder().queueUrl(queueUrl).build());

        String k3sReadyMsg = objectMapper.writeValueAsString(
            new ProgressMessage("t1", "k3s-ready", "k3s server running", 35));
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body(k3sReadyMsg).receiptHandle("rh1").build())
                .build());
        when(provisioningService.getProgress("t1"))
            .thenReturn(new TenantProgress("k3s-starting", "Starting k3s server", 20, null, 0, null, null));

        AtomicBoolean emittedTerminal = new AtomicBoolean(false);
        List<TenantProgress> results = invokePollProgressQueue("t1", emittedTerminal);

        assertFalse(emittedTerminal.get(), "k3s-ready must not be treated as terminal");
        assertEquals(1, results.size());
        assertEquals("k3s-ready", results.getFirst().state());
        verify(sqs, never()).deleteQueue(any(DeleteQueueRequest.class));
    }

    @Test
    void validateAndPersist_acceptsTerminalStateRegardlessOfProgress() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Installing", 90, null, 0, null, null));

        // "ready" with progress=100 should always be accepted even if current is 90
        ProgressMessage msg = new ProgressMessage("tenant-1", "ready", "Cluster ready", 100);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNotNull(result);
        assertEquals("ready", result.state());
        verify(provisioningService).updateProgressFromSqs("tenant-1", "ready", "Cluster ready", 100);
    }

    @Test
    void validateAndPersist_acceptsFailedState() throws Exception {
        when(provisioningService.getProgress("tenant-1"))
            .thenReturn(new TenantProgress("provisioning", "Installing", 50, null, 0, null, null));

        // "failed" can set progress to 0 — it's terminal, always accepted
        ProgressMessage msg = new ProgressMessage("tenant-1", "failed", "kubeadm init failed", 0);
        TenantProgress result = invokeValidateAndPersist("tenant-1", msg);

        assertNotNull(result);
        assertEquals("failed", result.state());
        verify(provisioningService).updateProgressFromSqs("tenant-1", "failed", "kubeadm init failed", 0);
    }

    // ── SQS polling tests ────────────────────────────────────────────────────

    @Test
    void pollProgressQueue_deletesQueueOnReadyState() throws Exception {
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/ecp-tenant-t1-progress.fifo";
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenReturn(GetQueueUrlResponse.builder().queueUrl(queueUrl).build());

        String readyMsg = objectMapper.writeValueAsString(
            new ProgressMessage("t1", "ready", "Cluster ready", 100));
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body(readyMsg).receiptHandle("rh1").build())
                .build());
        when(provisioningService.getProgress("t1"))
            .thenReturn(new TenantProgress("provisioning", "Installing", 90, "1.2.3.4", 0, null, null));

        AtomicBoolean emittedTerminal = new AtomicBoolean(false);
        List<TenantProgress> results = invokePollProgressQueue("t1", emittedTerminal);

        assertTrue(emittedTerminal.get());
        assertEquals(1, results.size());
        assertEquals("ready", results.getFirst().state());

        // Verify queue was deleted
        verify(sqs).deleteQueue(any(DeleteQueueRequest.class));
    }

    @Test
    void pollProgressQueue_emitsGapFillWhenQueueEmpty() throws Exception {
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/ecp-tenant-t1-progress.fifo";
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenReturn(GetQueueUrlResponse.builder().queueUrl(queueUrl).build());
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        when(provisioningService.getProgress("t1"))
            .thenReturn(new TenantProgress("provisioning", "EC2 instance launched", 0, null, 0, null, null));

        AtomicBoolean emittedTerminal = new AtomicBoolean(false);
        List<TenantProgress> results = invokePollProgressQueue("t1", emittedTerminal);

        assertFalse(emittedTerminal.get());
        assertEquals(1, results.size());
        assertEquals("Waiting for boot script...", results.getFirst().phase());
        assertEquals(25, results.getFirst().progress());
    }

    @Test
    void pollProgressQueue_fallsBackToDynamoDbWhenQueueDeleted() throws Exception {
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenThrow(QueueDoesNotExistException.builder().message("not found").build());
        when(provisioningService.getProgress("t1"))
            .thenReturn(new TenantProgress("ready", "Cluster ready", 100, "1.2.3.4", 0, null, null));

        AtomicBoolean emittedTerminal = new AtomicBoolean(false);
        List<TenantProgress> results = invokePollProgressQueue("t1", emittedTerminal);

        assertTrue(emittedTerminal.get());
        assertEquals(1, results.size());
        assertEquals("ready", results.getFirst().state());
    }

    // ── TenantNaming tests ───────────────────────────────────────────────────

    @Test
    void progressQueueName_followsConvention() {
        assertEquals("ecp-tenant-abc123-progress.fifo", TenantNaming.progressQueueName("abc123"));
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private void setField(String fieldName, Object value) throws Exception {
        Field field = TenantStreamResource.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(resource, value);
    }

    @SuppressWarnings("unchecked")
    private TenantProgress invokeValidateAndPersist(String tenantId, ProgressMessage msg) throws Exception {
        Method method = TenantStreamResource.class.getDeclaredMethod("validateAndPersist", String.class, ProgressMessage.class);
        method.setAccessible(true);
        return (TenantProgress) method.invoke(resource, tenantId, msg);
    }

    @SuppressWarnings("unchecked")
    private List<TenantProgress> invokePollProgressQueue(String tenantId, AtomicBoolean emittedTerminal) throws Exception {
        Method method = TenantStreamResource.class.getDeclaredMethod("pollProgressQueue", String.class, AtomicBoolean.class);
        method.setAccessible(true);
        return (List<TenantProgress>) method.invoke(resource, tenantId, emittedTerminal);
    }
}
