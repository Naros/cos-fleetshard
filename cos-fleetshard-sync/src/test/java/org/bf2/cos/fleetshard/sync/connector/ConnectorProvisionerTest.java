package org.bf2.cos.fleetshard.sync.connector;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_CLUSTER_ID;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_CONNECTOR_ID;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_DEPLOYMENT_ID;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_DEPLOYMENT_RESOURCE_VERSION;
import static org.bf2.cos.fleetshard.support.resources.Connectors.CONNECTOR_PREFIX;
import static org.bf2.cos.fleetshard.sync.connector.ConnectorTestSupport.createDeployment;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.bf2.cos.fleet.manager.model.ConnectorDeployment;
import org.bf2.cos.fleet.manager.model.KafkaConnectionSettings;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.ManagedConnectorBuilder;
import org.bf2.cos.fleetshard.support.resources.Secrets;
import org.bf2.cos.fleetshard.sync.client.FleetShardClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

public class ConnectorProvisionerTest {
    private static final String CLUSTER_ID = UUID.randomUUID().toString();

    @Test
    void createResources() {
        //
        // Given that no resources associated to the provided deployment exist
        //
        final ConnectorDeployment deployment = createDeployment(0);

        final List<ManagedConnector> connectors = List.of();
        final List<Secret> secrets = List.of();

        final FleetShardClient fleetShard = ConnectorTestSupport.fleetShard(CLUSTER_ID, connectors, secrets);
        final ConnectorDeploymentProvisioner provisioner = new ConnectorDeploymentProvisioner(fleetShard);
        final ArgumentCaptor<Secret> sc = ArgumentCaptor.forClass(Secret.class);
        final ArgumentCaptor<ManagedConnector> mcc = ArgumentCaptor.forClass(ManagedConnector.class);

        //
        // When deployment is applied
        //
        provisioner.provision(deployment);

        verify(fleetShard).createSecret(sc.capture());
        verify(fleetShard).createConnector(mcc.capture());

        //
        // Then resources must be created according to the deployment
        //
        assertThat(sc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .startsWith(CONNECTOR_PREFIX + "-")
                .endsWith("-" + deployment.getMetadata().getResourceVersion());

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, deployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, deployment.getId())
                .containsEntry(LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + deployment.getMetadata().getResourceVersion());

            assertThat(val.getData())
                .containsKey(Secrets.SECRET_ENTRY_KAFKA)
                .containsKey(Secrets.SECRET_ENTRY_CONNECTOR);

            var kafkaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_KAFKA, KafkaConnectionSettings.class);
            assertThat(kafkaNode.getBootstrapServer())
                .isEqualTo(deployment.getSpec().getKafka().getBootstrapServer());
            assertThat(kafkaNode.getClientSecret())
                .isEqualTo(deployment.getSpec().getKafka().getClientSecret());
            assertThat(kafkaNode.getClientId())
                .isEqualTo(deployment.getSpec().getKafka().getClientId());

            var connectorNode = Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR);
            assertThatJson(Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR))
                .inPath("connector.foo").isEqualTo("connector-foo");
            assertThatJson(connectorNode)
                .inPath("kafka.topic").isEqualTo("kafka-foo");

            var metaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_META);
            assertThatJson(metaNode)
                .isObject()
                .containsKey("connector_type")
                .containsKey("connector_image")
                .containsKey("kamelets")
                .containsKey("operators");

        });

        assertThat(mcc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .startsWith(CONNECTOR_PREFIX + "-");

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, deployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, deployment.getId());

            assertThat(val.getSpec().getDeployment()).satisfies(d -> {
                assertThat(d.getSecret()).isEqualTo(sc.getValue().getMetadata().getName());
            });
        });
    }

    @Test
    void updateResources() {
        //
        // Given that the resources associated to the provided deployment exist
        //
        final ConnectorDeployment oldDeployment = createDeployment(0);

        final List<ManagedConnector> connectors = List.of(
            new ManagedConnectorBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName("old-cid")
                    .addToLabels(LABEL_CLUSTER_ID, CLUSTER_ID)
                    .addToLabels(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                    .addToLabels(LABEL_DEPLOYMENT_ID, oldDeployment.getId())
                    .build())
                .build());
        final List<Secret> secrets = List.of(
            new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName("old-sid")
                    .addToLabels(LABEL_CLUSTER_ID, CLUSTER_ID)
                    .addToLabels(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                    .addToLabels(LABEL_DEPLOYMENT_ID, oldDeployment.getId())
                    .addToLabels(LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + oldDeployment.getMetadata().getResourceVersion())
                    .build())
                .build());

        final FleetShardClient fleetShard = ConnectorTestSupport.fleetShard(CLUSTER_ID, connectors, secrets);
        final ConnectorDeploymentProvisioner provisioner = new ConnectorDeploymentProvisioner(fleetShard);
        final ArgumentCaptor<Secret> sc = ArgumentCaptor.forClass(Secret.class);
        final ArgumentCaptor<ManagedConnector> mcc = ArgumentCaptor.forClass(ManagedConnector.class);

        //
        // When deployment is updated
        //
        final ConnectorDeployment newDeployment = createDeployment(0, d -> {
            d.getSpec().getKafka().setBootstrapServer("my-kafka.acme.com:218");
            ((ObjectNode) d.getSpec().getConnectorSpec()).with("connector").put("foo", "connector-baz");
            ((ObjectNode) d.getSpec().getShardMetadata()).put("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.1.0");
        });

        provisioner.provision(newDeployment);

        verify(fleetShard).createSecret(sc.capture());
        verify(fleetShard).createConnector(mcc.capture());

        //
        // Then the existing resources must be updated to reflect the changes made to the
        // deployment. This scenario could happen when a resource on the connector cluster
        // is amended outside the control of fleet manager (i.e. with kubectl) and in such
        // case, the expected behavior is that the resource is re-set to the configuration
        // from the fleet manager.
        //
        assertThat(sc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .isEqualTo("old-sid");

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, newDeployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, newDeployment.getId())
                .containsEntry(LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + newDeployment.getMetadata().getResourceVersion());

            assertThat(val.getData())
                .containsKey(Secrets.SECRET_ENTRY_KAFKA)
                .containsKey(Secrets.SECRET_ENTRY_CONNECTOR);

            var kafkaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_KAFKA, KafkaConnectionSettings.class);
            assertThat(kafkaNode.getBootstrapServer())
                .isEqualTo(newDeployment.getSpec().getKafka().getBootstrapServer());
            assertThat(kafkaNode.getClientSecret())
                .isEqualTo(newDeployment.getSpec().getKafka().getClientSecret());
            assertThat(kafkaNode.getClientId())
                .isEqualTo(newDeployment.getSpec().getKafka().getClientId());

            var connectorNode = Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR);
            assertThatJson(Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR))
                .inPath("connector.foo").isEqualTo("connector-baz");
            assertThatJson(connectorNode)
                .inPath("kafka.topic").isEqualTo("kafka-foo");

            var metaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_META);
            assertThatJson(metaNode)
                .isObject()
                .containsKey("connector_type")
                .containsKey("connector_image")
                .containsKey("kamelets")
                .containsKey("operators");
        });

        assertThat(mcc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .isEqualTo("old-cid");

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, oldDeployment.getId());

            assertThat(val.getSpec().getDeployment()).satisfies(d -> {
                assertThat(d.getDeploymentResourceVersion()).isEqualTo(oldDeployment.getMetadata().getResourceVersion());
                assertThat(d.getDeploymentResourceVersion()).isEqualTo(newDeployment.getMetadata().getResourceVersion());
                assertThat(d.getSecret()).isEqualTo(sc.getValue().getMetadata().getName());
            });
        });
    }

    @Test
    void updateAndCreateResources() {
        //
        // Given that the resources associated to the provided deployment exist
        //
        final ConnectorDeployment oldDeployment = createDeployment(0);

        final List<ManagedConnector> connectors = List.of(
            new ManagedConnectorBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName("old-cid")
                    .addToLabels(LABEL_CLUSTER_ID, CLUSTER_ID)
                    .addToLabels(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                    .addToLabels(LABEL_DEPLOYMENT_ID, oldDeployment.getId())
                    .build())
                .build());
        final List<Secret> secrets = List.of(
            new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName("old-sid")
                    .addToLabels(LABEL_CLUSTER_ID, CLUSTER_ID)
                    .addToLabels(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                    .addToLabels(LABEL_DEPLOYMENT_ID, oldDeployment.getId())
                    .addToLabels(LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + oldDeployment.getMetadata().getResourceVersion())
                    .build())
                .build());

        final FleetShardClient fleetShard = ConnectorTestSupport.fleetShard(CLUSTER_ID, connectors, secrets);
        final ConnectorDeploymentProvisioner provisioner = new ConnectorDeploymentProvisioner(fleetShard);
        final ArgumentCaptor<Secret> sc = ArgumentCaptor.forClass(Secret.class);
        final ArgumentCaptor<ManagedConnector> mcc = ArgumentCaptor.forClass(ManagedConnector.class);

        //
        // When a change to the deployment happen that ends up with a new resource version
        //
        final ConnectorDeployment newDeployment = createDeployment(1, d -> {
            d.getMetadata().setResourceVersion(1L);
            d.getSpec().getKafka().setBootstrapServer("my-kafka.acme.com:218");
            ((ObjectNode) d.getSpec().getConnectorSpec()).with("connector").put("foo", "connector-baz");
            ((ObjectNode) d.getSpec().getShardMetadata()).put("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.1.0");
        });

        provisioner.provision(newDeployment);

        verify(fleetShard).createSecret(sc.capture());
        verify(fleetShard).createConnector(mcc.capture());

        //
        // Then the managed connector resource is expected to be updated to reflect the
        // changes made to the deployment but a new secret should be created.
        //
        assertThat(sc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .isEqualTo("old-cid-" + newDeployment.getMetadata().getResourceVersion());

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, newDeployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, newDeployment.getId())
                .containsEntry(LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + newDeployment.getMetadata().getResourceVersion());

            assertThat(val.getData())
                .containsKey(Secrets.SECRET_ENTRY_KAFKA)
                .containsKey(Secrets.SECRET_ENTRY_CONNECTOR);

            var kafkaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_KAFKA, KafkaConnectionSettings.class);
            assertThat(kafkaNode.getBootstrapServer())
                .isEqualTo(newDeployment.getSpec().getKafka().getBootstrapServer());
            assertThat(kafkaNode.getClientSecret())
                .isEqualTo(newDeployment.getSpec().getKafka().getClientSecret());
            assertThat(kafkaNode.getClientId())
                .isEqualTo(newDeployment.getSpec().getKafka().getClientId());

            var connectorNode = Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR);
            assertThatJson(Secrets.extract(val, Secrets.SECRET_ENTRY_CONNECTOR))
                .inPath("connector.foo").isEqualTo("connector-baz");
            assertThatJson(connectorNode)
                .inPath("kafka.topic").isEqualTo("kafka-foo");

            var metaNode = Secrets.extract(val, Secrets.SECRET_ENTRY_META);
            assertThatJson(metaNode)
                .isObject()
                .containsKey("connector_type")
                .containsKey("connector_image")
                .containsKey("kamelets")
                .containsKey("operators");
        });

        assertThat(mcc.getValue()).satisfies(val -> {
            assertThat(val.getMetadata().getName())
                .isEqualTo("old-cid");

            assertThat(val.getMetadata().getLabels())
                .containsEntry(LABEL_CLUSTER_ID, CLUSTER_ID)
                .containsEntry(LABEL_CONNECTOR_ID, oldDeployment.getSpec().getConnectorId())
                .containsEntry(LABEL_DEPLOYMENT_ID, oldDeployment.getId());

            assertThat(val.getSpec().getDeployment()).satisfies(d -> {
                assertThat(d.getDeploymentResourceVersion()).isEqualTo(newDeployment.getMetadata().getResourceVersion());
                assertThat(d.getDeploymentResourceVersion()).isNotEqualTo(oldDeployment.getMetadata().getResourceVersion());
                assertThat(d.getSecret()).isEqualTo(sc.getValue().getMetadata().getName());
            });
        });
    }
}