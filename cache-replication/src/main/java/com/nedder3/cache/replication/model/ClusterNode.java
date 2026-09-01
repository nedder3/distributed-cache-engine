package com.nedder3.cache.replication.model;

import java.util.Objects;

/**
 * Represents a node in the distributed cache cluster.
 */
public record ClusterNode(String nodeId, String host, int port) {

    public ClusterNode {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(host, "host cannot be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
        }
    }

    public String address() {
        return host + ":" + port;
    }
}
