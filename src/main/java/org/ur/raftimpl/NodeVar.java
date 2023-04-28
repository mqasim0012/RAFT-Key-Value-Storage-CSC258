package org.ur.raftimpl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class NodeVar {
    // shared values within nodes, each node have their own
    public AtomicInteger term = new AtomicInteger(1); // default term of the
    public AtomicInteger votedFor = new AtomicInteger(-1);
    public AtomicBoolean receivedHeartBeat = new AtomicBoolean(false); // whether or not you received a heartbeat this term
    public AtomicReference<RaftNode.State> nodeState = new AtomicReference<>(RaftNode.State.FOLLOWER);
    public ConcurrentHashMap<String, String> logs = new ConcurrentHashMap<>();
    public AtomicReference<String> lastKey = new AtomicReference<>("");
    public AtomicReference<String> lastVal = new AtomicReference<>("");
}

class UniversalVar {
    // shared values across all nodes
    public ConcurrentHashMap<Integer, RaftClient> accessibleClients; // map of all client objects
    public AtomicInteger totalNodes; // total number of nodes
    public AtomicInteger leaderID;

    UniversalVar(ConcurrentHashMap<Integer, RaftClient> accessibleClients, AtomicInteger totalNodes, AtomicInteger leaderID) {
        this.accessibleClients = accessibleClients;
        this.totalNodes = totalNodes;
        this.leaderID = leaderID;
    }
}
