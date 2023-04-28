package org.ur.raftimpl;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.ur.comms.*;
import org.ur.raftimpl.RaftNode.State;

public class RaftClient implements Closeable {

    /*

      This is the client class for gRPC, not related to Raft
      This is used in RaftNode

      Clients in gRPC are used for sending requests to servers, so the functions
      here are used for communicating with their own server based on the channel
      and asking for a response

     */


    private final ManagedChannel channel;
    private final RaftServerGrpc.RaftServerBlockingStub blockingStub;

    public RaftClient(ManagedChannel channel) {
        this.channel = channel;
        this.blockingStub = RaftServerGrpc.newBlockingStub(channel);
    }

    public RaftClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port)
                // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
                // needing certificates.
                .usePlaintext()
                .build());
    }

    public VoteResponse requestVote(int sendID, int term) {
        VoteRequest request = VoteRequest.newBuilder()
                .setCandidateId(sendID)
                .setTerm(term)
                .build();
        return this.blockingStub.requestVote(request);
    }

    public AppendEntriesResponse appendEntry(int leaderID, int term, int commit, String prevKey, String prevValue, String newKey, String newValue) {
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                .setLeaderId(leaderID)
                .setTerm(term)
                .setCommit(commit)
                .setLastKey(prevKey)
                .setLastValue(prevValue)
                .setNewLogEntryKey(newKey)
                .setNewLogEntryValue(newValue)
                .build();

        return this.blockingStub.appendEntries(request);
    }

    @Override
    public void close() throws IOException {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new IOException(e.getMessage());
        }
    }
}
