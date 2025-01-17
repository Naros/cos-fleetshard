package org.bf2.cos.fleetshard.sync.it;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.ws.rs.core.MediaType;

import org.bf2.cos.fleet.manager.model.KafkaConnectionSettings;
import org.bf2.cos.fleet.manager.model.ServiceAccount;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.it.resources.OidcTestResource;
import org.bf2.cos.fleetshard.it.resources.WireMockTestResource;
import org.bf2.cos.fleetshard.support.resources.Resources;
import org.bf2.cos.fleetshard.support.resources.Secrets;
import org.bf2.cos.fleetshard.sync.it.support.SyncTestSupport;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.fabric8.kubernetes.api.model.Secret;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_READY;
import static org.bf2.cos.fleetshard.support.resources.Resources.uid;
import static org.bf2.cos.fleetshard.support.resources.Secrets.SECRET_ENTRY_CONNECTOR;
import static org.bf2.cos.fleetshard.support.resources.Secrets.SECRET_ENTRY_META;
import static org.bf2.cos.fleetshard.support.resources.Secrets.SECRET_ENTRY_SERVICE_ACCOUNT;
import static org.bf2.cos.fleetshard.support.resources.Secrets.toBase64;

@QuarkusTest
@TestProfile(ConnectorProvisionerTest.Profile.class)
public class ConnectorProvisionerTest extends SyncTestSupport {
    public static final String DEPLOYMENT_ID = uid();
    public static final String KAFKA_URL = "kafka.acme.com:2181";
    public static final String KAFKA_CLIENT_ID = uid();
    public static final String KAFKA_CLIENT_SECRET = toBase64(uid());

    @Test
    void connectorIsProvisioned() {
        {
            //
            // Deployment v1
            //

            RestAssured.given()
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.TEXT_PLAIN)
                .body(0L)
                .post("/test/connectors/deployment/provisioner/queue");

            Secret s1 = until(
                () -> fleetShardClient.getSecretByDeploymentId(DEPLOYMENT_ID),
                item -> Objects.equals(
                    "1",
                    item.getMetadata().getLabels().get(Resources.LABEL_DEPLOYMENT_RESOURCE_VERSION)));

            ManagedConnector mc = until(
                () -> fleetShardClient.getConnectorByDeploymentId(DEPLOYMENT_ID),
                item -> {
                    return item.getSpec().getDeployment().getDeploymentResourceVersion() == 1L
                        && item.getSpec().getDeployment().getSecret() != null;
                });

            assertThat(s1).satisfies(item -> {
                assertThat(item.getMetadata().getName())
                    .isEqualTo(Secrets.generateConnectorSecretId(mc.getSpec().getDeploymentId()));

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_SERVICE_ACCOUNT))
                    .isObject()
                    .containsEntry("client_id", KAFKA_CLIENT_ID)
                    .containsEntry("client_secret", KAFKA_CLIENT_SECRET);

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_CONNECTOR))
                    .inPath("connector")
                    .isObject()
                    .containsEntry("foo", "connector-foo");
                assertThatJson(Secrets.extract(item, SECRET_ENTRY_CONNECTOR))
                    .inPath("kafka")
                    .isObject()
                    .containsEntry("topic", "kafka-foo");

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_META))
                    .isObject()
                    .containsEntry("connector_type", "sink");
                assertThatJson(Secrets.extract(item, SECRET_ENTRY_META))
                    .isObject()
                    .containsEntry("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.0.1");
            });

            assertThat(mc.getMetadata().getName()).startsWith(Resources.CONNECTOR_PREFIX);
            assertThat(mc.getSpec().getDeployment().getKafka().getUrl()).isEqualTo(KAFKA_URL);
            assertThat(mc.getSpec().getDeployment().getSecret()).isEqualTo(s1.getMetadata().getName());
        }
        {
            //
            // Deployment v2
            //

            RestAssured.given()
                .contentType(MediaType.TEXT_PLAIN)
                .accept(MediaType.TEXT_PLAIN)
                .body(1L)
                .post("/test/connectors/deployment/provisioner/queue");

            Secret s1 = until(
                () -> fleetShardClient.getSecretByDeploymentId(DEPLOYMENT_ID),
                item -> Objects.equals(
                    "2",
                    item.getMetadata().getLabels().get(Resources.LABEL_DEPLOYMENT_RESOURCE_VERSION)));

            ManagedConnector mc = until(
                () -> fleetShardClient.getConnectorByDeploymentId(DEPLOYMENT_ID),
                item -> {
                    return item.getSpec().getDeployment().getDeploymentResourceVersion() == 2L
                        && item.getSpec().getDeployment().getSecret() != null;
                });

            assertThat(s1).satisfies(item -> {
                assertThat(item.getMetadata().getName())
                    .isEqualTo(Secrets.generateConnectorSecretId(mc.getSpec().getDeploymentId()));

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_SERVICE_ACCOUNT))
                    .isObject()
                    .containsEntry("client_id", KAFKA_CLIENT_ID)
                    .containsEntry("client_secret", KAFKA_CLIENT_SECRET);

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_CONNECTOR))
                    .inPath("connector")
                    .isObject()
                    .containsEntry("foo", "connector-bar");
                assertThatJson(Secrets.extract(item, SECRET_ENTRY_CONNECTOR))
                    .inPath("kafka")
                    .isObject()
                    .containsEntry("topic", "kafka-bar");

                assertThatJson(Secrets.extract(item, SECRET_ENTRY_META))
                    .isObject()
                    .containsEntry("connector_type", "sink");
                assertThatJson(Secrets.extract(item, SECRET_ENTRY_META))
                    .isObject()
                    .containsEntry("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.1.0");
            });

            assertThat(mc.getMetadata().getName()).startsWith(Resources.CONNECTOR_PREFIX);
            assertThat(mc.getSpec().getDeployment().getKafka().getUrl()).isEqualTo(KAFKA_URL);
            assertThat(mc.getSpec().getDeployment().getSecret()).isEqualTo(s1.getMetadata().getName());
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            final String ns = "cos-sync-" + uid();

            return Map.of(
                "cos.cluster.id", uid(),
                "test.namespace", ns,
                "cos.connectors.namespace", ns,
                "cos.operators.namespace", ns,
                "cos.cluster.status.sync-interval", "disabled",
                "cos.connectors.poll-interval", "disabled",
                "cos.connectors.resync-interval", "disabled",
                "cos.connectors.status.resync-interval", "disabled");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(
                new TestResourceEntry(OidcTestResource.class),
                new TestResourceEntry(FleetManagerTestResource.class));
        }
    }

    public static class FleetManagerTestResource extends WireMockTestResource {
        @Override
        protected Map<String, String> doStart(WireMockServer server) {
            final String clusterId = ConfigProvider.getConfig().getValue("cos.cluster.id", String.class);
            final String clusterUrl = "/api/connector_mgmt/v1/kafka_connector_clusters/" + clusterId;
            final String deploymentsUrl = clusterUrl + "/deployments";
            final String statusUrl = clusterUrl + "/deployments/" + DEPLOYMENT_ID + "/status";

            {
                //
                // Deployment v1
                //

                JsonNode list = deploymentList(
                    deployment(DEPLOYMENT_ID, 1L, spec -> {
                        spec.connectorId("connector-1");
                        spec.connectorTypeId("connector-type-1");
                        spec.connectorResourceVersion(1L);
                        spec.kafka(
                            new KafkaConnectionSettings()
                                .url(KAFKA_URL));
                        spec.serviceAccount(
                            new ServiceAccount()
                                .clientId(KAFKA_CLIENT_ID)
                                .clientSecret(KAFKA_CLIENT_SECRET));
                        spec.connectorSpec(node(n -> {
                            n.with("connector").put("foo", "connector-foo");
                            n.with("kafka").put("topic", "kafka-foo");
                        }));
                        spec.shardMetadata(node(n -> {
                            n.put("connector_type", "sink");
                            n.put("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.0.1");
                            n.withArray("operators").addObject()
                                .put("type", "camel-connector-operator")
                                .put("version", "[1.0.0,2.0.0)");
                        }));
                        spec.desiredState(DESIRED_STATE_READY);
                    }));

                MappingBuilder request = WireMock.get(WireMock.urlPathEqualTo(deploymentsUrl))
                    .withQueryParam("gt_version", equalTo("0"))
                    .withQueryParam("watch", equalTo("false"));
                ResponseDefinitionBuilder response = WireMock.aResponse()
                    .withHeader("Content-Type", APPLICATION_JSON)
                    .withJsonBody(list);

                server.stubFor(request.willReturn(response));
            }

            {
                //
                // Deployment v2
                //

                JsonNode list = deploymentList(
                    deployment(DEPLOYMENT_ID, 2L, spec -> {
                        spec.connectorId("connector-1");
                        spec.connectorTypeId("connector-type-1");
                        spec.connectorResourceVersion(1L);
                        spec.kafka(
                            new KafkaConnectionSettings()
                                .url(KAFKA_URL));
                        spec.serviceAccount(
                            new ServiceAccount()
                                .clientId(KAFKA_CLIENT_ID)
                                .clientSecret(KAFKA_CLIENT_SECRET));
                        spec.connectorSpec(node(n -> {
                            n.with("connector").put("foo", "connector-bar");
                            n.with("kafka").put("topic", "kafka-bar");
                        }));
                        spec.shardMetadata(node(n -> {
                            n.put("connector_type", "sink");
                            n.put("connector_image", "quay.io/mcs_dev/aws-s3-sink:0.1.0");
                            n.withArray("operators").addObject()
                                .put("type", "camel-connector-operator")
                                .put("version", "[1.0.0,2.0.0)");
                        }));
                        spec.desiredState(DESIRED_STATE_READY);
                    }));

                MappingBuilder request = WireMock.get(WireMock.urlPathEqualTo(deploymentsUrl))
                    .withQueryParam("gt_version", equalTo("1"))
                    .withQueryParam("watch", equalTo("false"));
                ResponseDefinitionBuilder response = WireMock.aResponse()
                    .withHeader("Content-Type", APPLICATION_JSON)
                    .withJsonBody(list);

                server.stubFor(request.willReturn(response));
            }
            {
                //
                // Status
                //

                MappingBuilder request = WireMock.put(WireMock.urlPathEqualTo(statusUrl));
                ResponseDefinitionBuilder response = WireMock.ok();

                server.stubFor(request.willReturn(response));
            }

            return Map.of("control-plane-base-url", server.baseUrl());
        }
    }
}
