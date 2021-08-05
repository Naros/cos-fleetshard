package org.bf2.cos.fleetshard.operator.it.support;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.awaitility.Awaitility;
import org.bf2.cos.fleetshard.api.ManagedConnector;
import org.bf2.cos.fleetshard.api.ManagedConnectorOperator;
import org.bf2.cos.fleetshard.api.ManagedConnectorOperatorBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnectorOperatorSpecBuilder;
import org.bf2.cos.fleetshard.api.OperatorSelector;
import org.bf2.cos.fleetshard.operator.client.FleetShardClient;
import org.bf2.cos.fleetshard.support.Constants;
import org.bf2.cos.fleetshard.support.ResourceUtil;
import org.bf2.cos.fleetshard.support.resources.Connectors;
import org.bf2.cos.fleetshard.support.resources.Secrets;
import org.bf2.cos.fleetshard.support.unstructured.UnstructuredClient;
import org.bf2.cos.fleetshard.support.unstructured.UnstructuredSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bf2.cos.fleetshard.api.ManagedConnector.CONTEXT_DEPLOYMENT;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_READY;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_CONTEXT;
import static org.bf2.cos.fleetshard.api.ManagedConnector.LABEL_WATCH;
import static org.bf2.cos.fleetshard.support.ResourceUtil.uid;
import static org.bf2.cos.fleetshard.support.resources.Secrets.toBase64;

public class CamelConnectorTestSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(CamelConnectorTestSupport.class);

    @KubernetesTestServer
    protected KubernetesServer ksrv;

    @ConfigProperty(name = "camel.meta.service.url")
    protected String camelMetaUrl;
    @ConfigProperty(name = "kubernetes.namespace")
    protected String namespace;
    @ConfigProperty(name = "cos.cluster.id")
    protected String clusterId;

    protected FleetShardClient fleetShard;
    protected UnstructuredClient uc;
    protected KubernetesClient kubernetesClient;
    protected ManagedConnectorOperator camelConnectorOperator;
    protected String kafkaUrl;
    protected String kafkaClientId;
    protected String kafkaClientSecret;
    protected Secret secret;
    protected ManagedConnector connector;

    protected static <T> T until(final Callable<Optional<T>> supplier, final Predicate<? super T> predicate) {
        return Awaitility.await()
            .atMost(30, TimeUnit.SECONDS)
            .pollDelay(100, TimeUnit.MILLISECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until(supplier, item -> item.filter(predicate).isPresent())
            .get();
    }

    @BeforeEach
    public void setUp() {
        this.kafkaUrl = "kafka.acme.com:2181";
        this.kafkaClientId = uid();
        this.kafkaClientSecret = toBase64(uid());
        this.kubernetesClient = ksrv.getClient();
        this.fleetShard = new FleetShardClient(ksrv.getClient(), namespace, namespace);
        this.uc = new UnstructuredClient(ksrv.getClient());

        this.camelConnectorOperator = new ManagedConnectorOperatorBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(uid())
                .build())
            .withSpec(new ManagedConnectorOperatorSpecBuilder()
                .withMetaService(camelMetaUrl)
                .withType("camel-connector-operator")
                .withVersion("1.5.0")
                .build())
            .build();

        fleetShard.create(camelConnectorOperator);

        final String deploymentId = "deployment-" + uid();
        final String connectorId = "connector-" + uid();
        final String secretName = "secret-" + uid();

        this.secret = Secrets.newSecret(
            secretName,
            clusterId,
            connectorId,
            deploymentId,
            1L,
            Map.of(LABEL_CONTEXT, CONTEXT_DEPLOYMENT, LABEL_WATCH, "true"));

        Secrets.set(secret, Constants.SECRET_ENTRY_CONNECTOR, Map.of(
            "connector", Map.of("foo", "bar"),
            "kafka", Map.of("topic", "kafka-topic")));
        Secrets.set(secret, Constants.SECRET_ENTRY_KAFKA, Map.of(
            "bootstrap_server", kafkaUrl,
            "client_id", kafkaClientId,
            "client_secret", kafkaClientSecret));
        Secrets.set(secret, Constants.SECRET_ENTRY_META, Map.of(
            "connector_type", "sink",
            "connector_image", "quay.io/mcs_dev/aws-s3-sink:0.1.0",
            "kamelets", Map.of("connector", "aws-s3-sink", "kafka", "kafka-source")));

        this.connector = Connectors.newConnector(
            ResourceUtil.generateConnectorId(),
            clusterId,
            connectorId,
            deploymentId,
            Map.of(LABEL_CONTEXT, CONTEXT_DEPLOYMENT));

        connector.getSpec().getDeployment().setConnectorResourceVersion(1L);
        connector.getSpec().getDeployment().setDeploymentResourceVersion(1L);
        connector.getSpec().getDeployment().setDesiredState(DESIRED_STATE_READY);
        connector.getSpec().getDeployment().setSecret(secretName);
        connector.getSpec().getDeployment().setSecretChecksum("TODO");
        connector.getSpec().getDeployment().setConnectorTypeId("connector_type_id");
        connector.getSpec().setOperatorSelector(new OperatorSelector(
            camelConnectorOperator.getMetadata().getName(),
            camelConnectorOperator.getSpec().getType(),
            "[1.0.0,2.0.0)"));

        this.fleetShard.create(secret);
        this.fleetShard.create(connector);
    }

    protected List<Secret> secrets() {
        List<Secret> answer = fleetShard.getKubernetesClient().secrets()
            .inNamespace(namespace)
            .withLabel(ManagedConnector.LABEL_DEPLOYMENT_ID)
            .list()
            .getItems();

        return answer != null ? answer : Collections.emptyList();
    }

    protected List<ManagedConnector> connectors() {
        return fleetShard.lookupManagedConnectors();
    }

    protected Optional<ManagedConnector> getConnectorByDeploymentId(String deploymentId) {
        var items = kubernetesClient.customResources(ManagedConnector.class)
            .inNamespace(namespace)
            .withLabel(LABEL_CONTEXT, CONTEXT_DEPLOYMENT)
            .withLabel(ManagedConnector.LABEL_CLUSTER_ID, clusterId)
            .withLabel(ManagedConnector.LABEL_DEPLOYMENT_ID, deploymentId)
            .list();

        if (items.getItems() != null && items.getItems().size() > 1) {
            throw new IllegalArgumentException(
                "Multiple connectors with id: " + deploymentId);
        }

        if (items.getItems() != null && items.getItems().size() == 1) {
            return Optional.of(items.getItems().get(0));
        }

        return Optional.empty();
    }

    protected Optional<Secret> getSecretByDeploymentIdAndRevision(String deploymentId, long revision) {
        var items = fleetShard.getKubernetesClient()
            .secrets()
            .inNamespace(namespace)
            .withLabel(LABEL_CONTEXT, CONTEXT_DEPLOYMENT)
            .withLabel(ManagedConnector.LABEL_CLUSTER_ID, clusterId)
            .withLabel(ManagedConnector.LABEL_DEPLOYMENT_ID, deploymentId)
            .withLabel(ManagedConnector.LABEL_DEPLOYMENT_RESOURCE_VERSION, "" + revision)
            .list();

        if (items.getItems() != null && items.getItems().size() > 1) {
            throw new IllegalArgumentException(
                "Multiple secret with id: " + deploymentId);
        }
        if (items.getItems() != null && items.getItems().size() == 1) {
            return Optional.of(items.getItems().get(0));
        }

        return Optional.empty();
    }

    public GenericKubernetesResource updateUnstructured(
        String apiVersion,
        String kind,
        String name,
        Consumer<GenericKubernetesResource> consumer) {

        return ksrv.getClient()
            .genericKubernetesResources(UnstructuredSupport.asCustomResourceDefinitionContext(apiVersion, kind))
            .inNamespace(namespace)
            .withName(name)
            .editStatus(item -> {
                consumer.accept(item);
                return item;
            });
    }
}