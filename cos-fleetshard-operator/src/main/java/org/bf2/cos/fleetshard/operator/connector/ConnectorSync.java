package org.bf2.cos.fleetshard.operator.connector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.scheduler.Scheduled;
import org.bf2.cos.fleet.manager.api.model.ConnectorDeployment;
import org.bf2.cos.fleetshard.api.ConnectorStatus;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.ManagedConnectorCluster;
import org.bf2.cos.fleetshard.api.StatusExtractor;
import org.bf2.cos.fleetshard.common.ResourceUtil;
import org.bf2.cos.fleetshard.common.UnstructuredClient;
import org.bf2.cos.fleetshard.operator.controlplane.ControlPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the synchronization protocol for the connectors.
 */
@ApplicationScoped
public class ConnectorSync {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectorSync.class);

    @Inject
    ControlPlane controlPlane;
    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    UnstructuredClient uc;

    @Scheduled(every = "{cos.connectors.sync.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sync() {
        LOGGER.debug("Sync connectors");

        String namespace = kubernetesClient.getNamespace();
        KubernetesResourceList<ManagedConnectorCluster> items = kubernetesClient.customResources(ManagedConnectorCluster.class)
                .inNamespace(namespace).list();

        if (items.getItems().isEmpty()) {
            LOGGER.debug("Agent not yet configured");
            return;
        }
        if (items.getItems().size() > 1) {
            // TODO: report the failure status to the CR and control plane
            LOGGER.warn("More than one Agent");
            return;
        }

        ManagedConnectorCluster agent = items.getItems().get(0);
        if (agent.getStatus() == null || !Objects.equals(agent.getStatus().getPhase(), "ready")) {
            LOGGER.debug("Agent not yet configured");
            return;
        }

        LOGGER.debug("Polling for control plane connectors");
        for (var deployment : controlPlane.getConnectors(agent)) {
            try {
                provision(agent, deployment);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void provision(
            ManagedConnectorCluster agent,
            ConnectorDeployment deployment)
            throws Exception {

        LOGGER.info("deploying connector {}, {}", deployment.getId(), deployment.getSpec());

        if (deployment.getMetadata() == null) {
            throw new IllegalArgumentException("Metadata must be defined");
        }
        if (deployment.getMetadata().getResourceVersion() == null) {
            throw new IllegalArgumentException("Resource Version must be defined");
        }
        if (deployment.getSpec() == null) {
            throw new IllegalArgumentException("Spec must be defined");
        }

        ManagedConnector managedConnector = kubernetesClient.customResources(ManagedConnector.class)
                .inNamespace(agent.getMetadata().getNamespace())
                .withName(deployment.getId())
                .get();

        if (managedConnector != null) {
            //
            // If the connector resource version is greater than the resource version of the deployment
            // request, skip it as we assume nothing has changed.
            //
            // TODO: this should not happen as the connector poll procedure should automatically filter
            //       out any unwanted resources so we may want to remove this once the system is proven
            //       to be stable enough.
            //
            if (managedConnector.getSpec().getConnectorResourceVersion() > deployment.getMetadata().getResourceVersion()) {
                return;
            }
        } else {
            managedConnector = new ManagedConnector();
            managedConnector.getMetadata().setName(deployment.getId());
            managedConnector.getMetadata().setOwnerReferences(List.of(ResourceUtil.asOwnerReference(agent)));
            managedConnector.getSpec().setClusterId(agent.getSpec().getId());
        }

        //
        // Set the phase to provisioning so we know that this connector set-up has not yet
        // deployed
        //
        managedConnector.getStatus().setPhase(ConnectorStatus.PhaseType.Provisioning);
        managedConnector = kubernetesClient.customResources(ManagedConnector.class)
                .inNamespace(agent.getMetadata().getNamespace())
                .createOrReplace(managedConnector);

        if (deployment.getSpec().getStatusExtractors() != null) {
            var extractors = deployment.getSpec().getStatusExtractors().stream().map(se -> {
                var answer = new StatusExtractor();
                answer.setApiVersion(se.getApiVersion());
                answer.setKind(se.getKind());
                answer.setName(se.getName());
                answer.setConditionsPath(se.getJsonPath());
                if (se.getConditionType() != null) {
                    answer.setConditionTypes(List.of(se.getConditionType()));
                }

                return answer;
            }).collect(Collectors.toList());

            managedConnector.getSpec().setStatusExtractors(extractors);
        }

        managedConnector.getSpec().setConnectorResourceVersion(deployment.getMetadata().getResourceVersion());
        managedConnector.getSpec().setResources(new ArrayList<>());

        if (deployment.getSpec().getResources() != null) {
            for (JsonNode node : deployment.getSpec().getResources()) {
                Map<String, Object> result = uc.createOrReplace(
                        agent.getMetadata().getNamespace(),
                        node);

                managedConnector.getSpec().getResources().add(ResourceUtil.asResourceRef(result));
            }
        }

        managedConnector.getStatus().setPhase(ConnectorStatus.PhaseType.Provisioned);
        kubernetesClient.customResources(ManagedConnector.class)
                .inNamespace(agent.getMetadata().getNamespace())
                .createOrReplace(managedConnector);

    }
}
