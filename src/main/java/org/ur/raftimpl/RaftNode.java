package org.ur.raftimpl;

import org.ur.comms.AppendEntriesResponse;
import org.ur.comms.VoteResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.*;

public class RaftNode {

    /*
     * 
     * This represent individual Raft Nodes by using a combination of gRPC client
     * and server and attaching them
     * to this class.
     * 
     * This is where most stuff happens
     * 
     * This class kickstarts the server, which can respond, and starts the client,
     * which can request
     * 
     * This is where the most of the logic will take place,
     * for example, node state (follower, candidate, leader), timeouts, logs, etc.
     * 
     */

    enum State {
        FOLLOWER,
        LEADER,
        CANDIDATE
    }

    int nodeID; // id
    int port; // port
    long totalTime = 0;
    int appendNum = 0;

    // a clump of Atomic values that needs to be passed around, clumped together for
    // simplicity in code
    // not sure if best idea
    NodeVar nV = new NodeVar();
    UniversalVar uV;

    // all variables related to the task of time out loop and heartbeat loop
    ScheduledExecutorService executor;

    ScheduledFuture<?> timeoutTask;
    ScheduledFuture<?> heartbeatTask;

    Runnable timeoutCycle;
    Runnable heartbeatCycle;

    // TODO these values need tweaking
    // broadcastTime << electionTimeout << mean time between failures of
    // participants
    long timeout = 500L; // timeout between 50 to 500 ms, we won't have a lot of timeouts so we can set
                         // it higher
    long initDelay = 10000L; // suggested to be 10s+ to give server time to start
    int heartbeatInterval = 1; // sending heartbeat to 10 nodes takes from 10 to 40 ms, and to 3 nodes 5 - 20
                               // ms choose depending on node number
    TimeUnit unit = TimeUnit.SECONDS; // suggested using milliseconds

    // log replication elements
    Queue<String> newKeys = new LinkedList<>();
    Queue<String> newValues = new LinkedList<>();

    // -1 represent heartbeat, 0 represent there were entries in the previous term
    // and there was a majority vote, 1 means commit
    int commitChange = -1;

    public RaftNode(int nodeID, int port, UniversalVar uV, int timeout, int initDelay) {
        this.nodeID = nodeID;
        this.port = port;
        this.timeout = timeout;
        this.initDelay = initDelay;
        this.uV = uV;

        // if id already exist, do nothing
        if (this.uV.accessibleClients.get(nodeID) != null) {
            System.out.println("nodeID: " + nodeID + " is taken, please try something else, exiting...");
            return;
        }

        // start server and client on port number
        this.start();

        timeoutCycle = () -> {
            // System.out.println("nodeID: " + nodeID + " logs: " +
            // Collections.singletonList(this.nV.logs));
            if (!nV.receivedHeartBeat.get()) {
                // timed out, no heartbeat received
                // System.out.println("nodeID: " + nodeID + " Heartbeat not received, timing
                // out");
                selfElect();
            } else {
                // System.out.println("nodeID: " + nodeID + " Heartbeat received");
            }
            // reset heartbeat
            nV.receivedHeartBeat.set(false);
        };

        heartbeatCycle = this::appendNewEntries;

        executor = Executors.newScheduledThreadPool(2);
        this.startHeartBeatMonitor();
    }

    // starting the node
    private void start() {
        // kickstart server and client
        this.uV.totalNodes.incrementAndGet();
        this.startClient();
        this.startServer();
    }

    // starting gRPC server
    private void startServer() {
        // kickstart server creating new thread
        Thread serverThread = new Thread(() -> {
            try {
                // setting up the server with those variables allows the server thread to edit
                // those variables
                final RaftServer server = new RaftServer(port, nV, this.nodeID);
                server.start();
                server.blockUntilShutdown();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
        serverThread.start();
    }

    // starting gRPC client
    private void startClient() {
        // put new client into hashmap to create an entry point into this node
        uV.accessibleClients.putIfAbsent(this.nodeID, new RaftClient("localhost", port));
    }

    // starting the timeout cycle
    private void startHeartBeatMonitor() {
        timeoutTask = executor.scheduleWithFixedDelay(timeoutCycle, this.initDelay, this.timeout, unit);
    }

    // stopping the timeout cycle
    private void stopHeartBeatMonitor() {
        if (nV.nodeState.get() != State.LEADER) {
            System.out.println("nodeID: " + nodeID + " Cannot stop timeout monitor since node is not a leader");
            return;
        }
        timeoutTask.cancel(false);
    }

    // starting the heartbeat from leader
    private void startHeartBeat() {
        if (nV.nodeState.get() != State.LEADER) {
            System.out.println("nodeID: " + nodeID + " Cannot start heartbeat since node is not a leader");
        }
        heartbeatTask = executor.scheduleWithFixedDelay(heartbeatCycle, 0, heartbeatInterval, unit);
    }

    // stopping the heartbeat, no longer leader
    private void stopHeartBeat() {
        heartbeatTask.cancel(false);
    }

    // elect self when timed out
    public void selfElect() {
        System.out.println("nodeID: " + nodeID + " Election started for");

        // this function is used to change state from a follower to a candidate and this
        // will sent out requestVote messages to all followers
        // auto voted for self & increment self term by 1
        int totalVotes = 1;
        int totalResponse = 1;
        this.nV.term.incrementAndGet();

        this.nV.nodeState.set(State.CANDIDATE);

        // gather votes
        for (int i = 0; i < uV.totalNodes.get(); i++) {
            if (i == nodeID) {
                continue;
            }

            try {
                // contacting gRPC clients
                VoteResponse response = uV.accessibleClients.get(i).requestVote(i, nV.term.get());
                totalResponse++;
                if (response.getGranted()) {
                    totalVotes++;
                }
            } catch (Exception e) {
                System.out.println(
                        "\nnodeID: " + nodeID + " EXCEPTION OCCURRED IN REQUESTING VOTES, ASSUMING FOLLOWER IS DOWN");
            }
        }

        // check the total number of response, if response is not equal to the
        // totalNodes, there can be two explanations
        checkNodeFailure(totalResponse);

        // if candidate gets the majority of votes, then becomes leader
        if (totalVotes > (uV.totalNodes.get() / 2)) {
            if (nV.receivedHeartBeat.get()) {
                // heart beat was received from new / previous leader during requesting votes
                this.nV.nodeState.set(State.FOLLOWER);
                return;
            } else {
                this.nV.nodeState.set(State.LEADER);
                this.uV.leaderID.set(nodeID);
                System.out.println("nodeID: " + nodeID + " Votes: " + totalVotes);
                System.out.println("nodeID: " + nodeID + " New leader established!\n");
                // send heartbeat to others to assert dominance once leader
                // stop heartbeat monitor
                this.nV.receivedHeartBeat.set(false);
                this.stopHeartBeatMonitor();
                this.startHeartBeat();
                return;
            }
        }
        System.out.println("nodeID: " + nodeID + " Voting tie/failed, candidate reverting to follower");
        this.nV.nodeState.set(State.FOLLOWER);
    }

    // function used in startHeartBeat and to append new entries
    public void appendNewEntries() {
        long startTime = System.nanoTime();

        int totalVotes = 0;
        String newKey = "";
        String newValue = "";

        // another leader exists, step down as leader
        if (this.nV.receivedHeartBeat.get()) {
            System.out.println("nodeID: " + nodeID + " Another leader is found, reverting back to follower");
            this.nV.nodeState.set(State.FOLLOWER);
            this.stopHeartBeat();
            this.startHeartBeatMonitor();
        } else {
            // dequeue one at a time
            int totalResponse = 1;
            boolean incomingMsg = false;

            if (!newKeys.isEmpty()) {
                newKey = newKeys.poll();
                newValue = newValues.poll();
                incomingMsg = true;
            } else {
                System.out.println("\n\n\nWE ARE DONE!!!\n\n\n");
            }

            // once a candidate becomes a leader, it sends heartbeat messages to establish
            // authority
            for (int i = 0; i < uV.totalNodes.get(); i++) {
                if (i == nodeID) {
                    continue;
                }
                try {
                    AppendEntriesResponse response = uV.accessibleClients.get(i)
                            .appendEntry(this.nodeID, this.nV.term.get(), this.commitChange, this.nV.lastKey.get(),
                                    this.nV.lastVal.get(), newKey, newValue);

                    if (response.getSuccess()) {
                        totalVotes++;
                    }
                    totalResponse++;
                } catch (Exception e) {
                    System.out.println("\nnodeID: " + nodeID
                            + " EXCEPTION OCCURRED IN SENDING HEARTBEAT, ASSUMING FOLLOWER IS DOWN");
                }
            }
            this.nV.lastKey.set(newKey);
            this.nV.lastVal.set(newValue);

            this.commitChange = (incomingMsg) ? 0 : -1;

            if (totalVotes > (uV.totalNodes.get() / 2)) {
                if (this.commitChange == 0) {
                    this.commitChange = 1;
                    this.nV.logs.put(newKey, newValue);
                }
            }
            // check the total number of response, if response is not equal to the
            // totalNodes, there can be two explanations
            checkNodeFailure(totalResponse);
            long estimatedTime = System.nanoTime() - startTime;
            this.totalTime += TimeUnit.NANOSECONDS.toMillis(estimatedTime);
            this.appendNum++;
            System.out.println("Total Time: " + totalTime + "ms for " + this.appendNum + " iteration");
        }
    }

    // helper logic
    private void checkNodeFailure(int totalResponse) {
        if (totalResponse < uV.totalNodes.get()) {
            System.out.println("nodeID: " + nodeID + " Could not reach a follower, assuming that follower is down");
            uV.totalNodes.decrementAndGet();
        } else if (totalResponse > uV.totalNodes.get()) {
            System.out.println(
                    "nodeID: " + nodeID + " Contacted a previously unreachable node, assume that follower is up");
            uV.totalNodes.incrementAndGet();
        }
    }

    // helper put
    public void put(String key, String value) {
        this.newKeys.add(key);
        this.newValues.add(value);
    }

    // helper get
    public String get(String key) {
        return this.nV.logs.get(key);
    }
}
