package org.ur.raftimpl;

import io.grpc.stub.StreamObserver;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Date;

import org.ur.comms.*;

public class RaftImpl extends RaftServerGrpc.RaftServerImplBase {

    /*
     * 
     * This is the implementation of the proto file, this specifies behaviors for
     * the server
     * for example, if it receives a requestVote request, it will send back a
     * VoteResponse object
     * 
     * It is used by Raft Servers
     * 
     */

    int port; // debugging
    int nodeID;
    AtomicInteger term;
    AtomicBoolean receivedHeartbeat;
    AtomicReference<RaftNode.State> nodeState;
    AtomicInteger votedFor;
    ConcurrentHashMap<String, String> log;

    AtomicReference<String> lastKey;
    AtomicReference<String> lastVal;
    Queue<String> qKey = new LinkedList<>();
    Queue<String> qVal = new LinkedList<>();

    public RaftImpl(int port, NodeVar nV, int nodeID) {
        this.port = port;
        this.log = nV.logs;
        this.term = nV.term;
        this.lastKey = nV.lastKey;
        this.lastVal = nV.lastVal;
        this.votedFor = nV.votedFor;
        this.nodeState = nV.nodeState;
        this.receivedHeartbeat = nV.receivedHeartBeat;
        this.nodeID = nodeID;
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        boolean granted = false;
        // cannot request votes from candidate or leaders
        if (nodeState.get() != RaftNode.State.CANDIDATE && nodeState.get() != RaftNode.State.LEADER) {
            // cannot request votes from those who already voted
            if (votedFor.get() == -1) {
                // vote is false from those with higher term than candidates
                if (request.getTerm() >= this.term.get()) {
                    this.votedFor.set(request.getCandidateId());
                    this.term.set(request.getTerm());
                    granted = true;
                }
            }
        }

        VoteResponse response = VoteResponse.newBuilder()
                .setGranted(granted)
                .setTerm(this.term.get())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        String newKey = request.getNewLogEntryKey();
        String newValue = request.getNewLogEntryValue();
        boolean success = request.getTerm() >= this.term.get();
        Date date = new Date();

        AppendEntriesResponse response;

        if (request.getCommit() == 1) {
            // System.out.println("Adding into state log");
            while (!qKey.isEmpty() && !qVal.isEmpty()) {
                // put key and value into state
                log.put(qKey.remove(), qVal.remove());
            }
        }

        // if log entry is empty then it is a heartbeat request
        if (!newKey.isEmpty() && !newValue.isEmpty()) {
            // if matches, add to buffer, if not, add last value
            if (request.getLastKey().equals(this.lastKey.get()) && request.getLastValue().equals(this.lastVal.get())) {
                // System.out.println("Adding entry to buffer");
            } else {
                // not full implementation of log replication
                System.out.println("My prev: " + this.lastKey.get() + " Their Prev: " + request.getLastKey());
                // System.out.println("Entry not matched!");
                log.put(this.lastKey.get(), this.lastVal.get());
            }
            this.qKey.add(newKey);
            this.qVal.add(newValue);
            this.lastKey.set(newKey);
            this.lastVal.set(newValue);

            System.out.println("Node " + this.nodeID + " synced at " + date.getTime());
        }

        // set heartbeat to true and return response
        this.receivedHeartbeat.set(true);
        response = AppendEntriesResponse.newBuilder()
                .setTerm(this.term.get())
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
