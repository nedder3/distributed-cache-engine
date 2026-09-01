package com.nedder3.cache.replication.membership;

import com.nedder3.cache.replication.model.ClusterNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages cluster membership, active nodes, and topology change notifications.
 */
public class ClusterMembership {

    private final ClusterNode selfNode;
    private final Map<String, ClusterNode> members = new ConcurrentHashMap<>();
    private final List<MembershipListener> listeners = new CopyOnWriteArrayList<>();

    public interface MembershipListener {
        void onNodeJoined(ClusterNode node);
        void onNodeLeft(ClusterNode node);
    }

    public ClusterMembership(ClusterNode selfNode) {
        this.selfNode = Objects.requireNonNull(selfNode, "selfNode cannot be null");
        this.members.put(selfNode.nodeId(), selfNode);
    }

    public ClusterNode self() {
        return selfNode;
    }

    public void addMember(ClusterNode node) {
        Objects.requireNonNull(node, "node cannot be null");
        ClusterNode prev = members.put(node.nodeId(), node);
        if (prev == null) {
            listeners.forEach(l -> l.onNodeJoined(node));
        }
    }

    public void removeMember(String nodeId) {
        if (nodeId == null || nodeId.equals(selfNode.nodeId())) {
            return; // Cannot remove self
        }
        ClusterNode removed = members.remove(nodeId);
        if (removed != null) {
            listeners.forEach(l -> l.onNodeLeft(removed));
        }
    }

    public List<ClusterNode> getRemoteMembers() {
        return members.values().stream()
                .filter(n -> !n.nodeId().equals(selfNode.nodeId()))
                .toList();
    }

    public List<ClusterNode> getAllMembers() {
        return List.copyOf(members.values());
    }

    public int size() {
        return members.size();
    }

    public void addListener(MembershipListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(MembershipListener listener) {
        listeners.remove(listener);
    }
}
