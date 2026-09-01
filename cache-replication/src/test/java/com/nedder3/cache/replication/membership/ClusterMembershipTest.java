package com.nedder3.cache.replication.membership;

import com.nedder3.cache.replication.model.ClusterNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterMembershipTest {

    private ClusterMembership membership;
    private final ClusterNode node1 = new ClusterNode("node-1", "127.0.0.1", 8081);
    private final ClusterNode node2 = new ClusterNode("node-2", "127.0.0.1", 8082);

    @BeforeEach
    void setUp() {
        membership = new ClusterMembership(node1);
    }

    @Test
    @DisplayName("Initial membership contains self node")
    void initialMembership() {
        assertThat(membership.self()).isEqualTo(node1);
        assertThat(membership.size()).isEqualTo(1);
        assertThat(membership.getRemoteMembers()).isEmpty();
    }

    @Test
    @DisplayName("Adding member notifies listeners and updates remotes")
    void addMember_notifiesListeners() {
        AtomicBoolean joined = new AtomicBoolean(false);
        membership.addListener(new ClusterMembership.MembershipListener() {
            @Override
            public void onNodeJoined(ClusterNode node) {
                if (node.nodeId().equals("node-2")) joined.set(true);
            }

            @Override
            public void onNodeLeft(ClusterNode node) {}
        });

        membership.addMember(node2);

        assertThat(membership.size()).isEqualTo(2);
        assertThat(membership.getRemoteMembers()).containsExactly(node2);
        assertThat(joined).isTrue();
    }
}
