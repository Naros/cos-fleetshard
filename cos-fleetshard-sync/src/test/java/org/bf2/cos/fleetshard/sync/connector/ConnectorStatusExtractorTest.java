package org.bf2.cos.fleetshard.sync.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_DELETED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_READY;
import static org.bf2.cos.fleetshard.api.ManagedConnector.DESIRED_STATE_STOPPED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_DE_PROVISIONING;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_FAILED;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_PROVISIONING;
import static org.bf2.cos.fleetshard.api.ManagedConnector.STATE_READY;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bf2.cos.fleet.manager.model.ConnectorDeploymentStatus;
import org.bf2.cos.fleetshard.api.ConnectorStatusSpecBuilder;
import org.bf2.cos.fleetshard.api.DeploymentSpecBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnectorBuilder;
import org.bf2.cos.fleetshard.api.ManagedConnectorStatus;
import org.bf2.cos.fleetshard.api.ManagedConnectorStatusBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;

public class ConnectorStatusExtractorTest {

    public static Stream<Arguments> defaultIfPhaseIsNotSet() {
        return Stream.of(
            arguments(
                DESIRED_STATE_READY,
                STATE_PROVISIONING),
            arguments(
                DESIRED_STATE_STOPPED,
                STATE_DE_PROVISIONING),
            arguments(
                DESIRED_STATE_DELETED,
                STATE_DE_PROVISIONING));
    }

    public static Stream<Arguments> extractFromConnectorStatus() {
        return Stream.of(
            arguments(
                DESIRED_STATE_READY,
                STATE_FAILED,
                STATE_FAILED,
                List.of(new ConditionBuilder()
                    .withType("Ready")
                    .withStatus("False")
                    .withReason("reason")
                    .withMessage("message")
                    .build())),
            arguments(
                DESIRED_STATE_READY,
                STATE_READY,
                STATE_READY,
                List.of(new ConditionBuilder()
                    .withType("Ready")
                    .withStatus("False")
                    .withReason("reason")
                    .withMessage("message")
                    .build())),
            arguments(
                DESIRED_STATE_READY,
                null,
                STATE_PROVISIONING,
                List.of(new ConditionBuilder()
                    .withType("Ready")
                    .withStatus("False")
                    .withReason("reason")
                    .withMessage("message")
                    .build())));
    }

    /*
     * Test that if no phase can be computed, then phase is set to a transient
     * phase according to the desired state.
     */
    @ParameterizedTest
    @MethodSource
    void defaultIfPhaseIsNotSet(
        String statusDesiredState,
        String expectedState) {

        var status = ConnectorStatusExtractor.extract(
            new ManagedConnectorBuilder()
                .withStatus(new ManagedConnectorStatusBuilder()
                    .withPhase(ManagedConnectorStatus.PhaseType.Monitor)
                    .withDeployment(new DeploymentSpecBuilder()
                        .withDeploymentResourceVersion(1L)
                        .withDesiredState(statusDesiredState)
                        .build())
                    .build())
                .build());

        assertThat(status.getPhase()).isEqualTo(expectedState);
        assertThat(status.getConditions()).isNullOrEmpty();
        assertThat(status.getResourceVersion()).isEqualTo(1L);

        assertThat(status)
            .extracting(ConnectorDeploymentStatus::getOperators)
            .hasAllNullFieldsOrProperties();
    }

    /*
     * Test that if the status sub resource is provided and the phase is
     * "monitor", then the status extractor compute the phase according
     * to the reported deployment status
     */
    @ParameterizedTest
    @MethodSource
    void extractFromConnectorStatus(
        String statusDesiredState,
        String connectorPhase,
        String expectedState,
        List<Condition> conditions) {

        var status = ConnectorStatusExtractor.extract(
            new ManagedConnectorBuilder()
                .withStatus(new ManagedConnectorStatusBuilder()
                    .withPhase(ManagedConnectorStatus.PhaseType.Monitor)
                    .withDeployment(new DeploymentSpecBuilder()
                        .withDeploymentResourceVersion(1L)
                        .withDesiredState(statusDesiredState)
                        .build())
                    .withConnectorStatus(new ConnectorStatusSpecBuilder()
                        .withPhase(connectorPhase)
                        .withConditions(conditions)
                        .build())
                    .build())
                .build());

        var v1Conditions = conditions.stream()
            .map(ConnectorStatusExtractor::toMetaV1Condition)
            .collect(Collectors.toList());

        assertThat(status.getPhase()).isEqualTo(expectedState);
        assertThat(status.getConditions()).hasSameSizeAs(conditions).hasSameElementsAs(v1Conditions);
        assertThat(status.getResourceVersion()).isEqualTo(1L);

        assertThat(status)
            .extracting(ConnectorDeploymentStatus::getOperators)
            .hasAllNullFieldsOrProperties();
    }
}