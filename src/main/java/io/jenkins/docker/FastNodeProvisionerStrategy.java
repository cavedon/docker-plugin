package io.jenkins.docker;

import static hudson.slaves.NodeProvisioner.Strategy;
import static hudson.slaves.NodeProvisioner.StrategyDecision;
import static hudson.slaves.NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
import static hudson.slaves.NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.nirima.jenkins.plugins.docker.DockerCloud;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Based on <a href="https://github.com/jenkinsci/one-shot-executor-plugin/blob/master/src/main/java/org/jenkinsci/plugins/oneshot/OneShotProvisionerStrategy.java">OneShotProvisionerStrategy</a>
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class FastNodeProvisionerStrategy extends Strategy {

    private static final Logger LOGGER = Logger.getLogger(FastNodeProvisionerStrategy.class.getName());

    @NonNull
    @Override
    public StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {
        if (Jenkins.get().isQuietingDown()) {
            return CONSULT_REMAINING_STRATEGIES;
        }
        ArrayList<DockerCloud> dockerClouds = new ArrayList<DockerCloud>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof DockerCloud) {
                dockerClouds.add((DockerCloud) cloud);
            }
        }
        if (DockerGlobalConfiguration.get().getRandomizeCloudsOrder()) {
            Collections.shuffle(dockerClouds);
        }
        for (DockerCloud cloud : dockerClouds) {
            final StrategyDecision decision = applyToCloud(state, cloud);
            if (decision == PROVISIONING_COMPLETED) {
                return decision;
            }
        }
        return CONSULT_REMAINING_STRATEGIES;
    }

    private StrategyDecision applyToCloud(@NonNull NodeProvisioner.StrategyState state, DockerCloud cloud) {

        final Label label = state.getLabel();

        if (!cloud.canProvision(label)) {
            return CONSULT_REMAINING_STRATEGIES;
        }

        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();
        LOGGER.log(FINEST, "Available executors={0}, connecting={1}, planned={2}", new Object[] {
            snapshot.getAvailableExecutors(), snapshot.getConnectingExecutors(), state.getPlannedCapacitySnapshot()
        });
        int availableCapacity = snapshot.getAvailableExecutors()
                + snapshot.getConnectingExecutors()
                + state.getPlannedCapacitySnapshot();

        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(FINE, "Available capacity={0}, currentDemand={1}", new Object[] {availableCapacity, currentDemand});

        if (availableCapacity < currentDemand) {
            Collection<NodeProvisioner.PlannedNode> plannedNodes =
                    cloud.provision(label, currentDemand - availableCapacity);
            LOGGER.log(FINE, "Planned {0} new nodes", plannedNodes.size());
            state.recordPendingLaunches(plannedNodes);
            availableCapacity += plannedNodes.size();
            LOGGER.log(FINE, "After provisioning, available capacity={0}, currentDemand={1}", new Object[] {
                availableCapacity, currentDemand
            });
        }

        if (availableCapacity >= currentDemand) {
            LOGGER.log(FINE, "Provisioning completed");
            return PROVISIONING_COMPLETED;
        }
        LOGGER.log(FINE, "Provisioning not complete, consulting remaining strategies");
        return CONSULT_REMAINING_STRATEGIES;
    }

    /**
     * Ping the nodeProvisioner as a new task enters the queue, so it can provision a DockerSlave without delay.
     */
    @Extension
    public static class FastProvisionning extends QueueListener {
        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            final Jenkins jenkins = Jenkins.get();
            final Label label = item.getAssignedLabel();
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof DockerCloud && cloud.canProvision(label)) {
                    final NodeProvisioner provisioner =
                            (label == null ? jenkins.unlabeledNodeProvisioner : label.nodeProvisioner);
                    provisioner.suggestReviewNow();
                }
            }
        }
    }
}
