RAFT Key-Value Store Implementation
Co-authors:
    Donovan Zhong (dzhong2@u.rochester.edu)
    Muhammad Qasim (mqasim@u.rochester.edu)

How to run:
*Make sure you have gradle installed
$ ./gradlew build
$ ./gradlew run --console=plain

# Raft gRPC Implementation

* ``raft.proto``: defines behaviors of gRPC server  
* ``RaftImpl``: implements behaviors of gRPC server
* ``RaftServer``: uses RaftImpl and attaches it to a server
* ``RaftClient``: implements client side code of gRPC
* ``RaftNode``: combines gRPC server and client to create a node capable of responding and sending messages
* ``RaftSystem``: the entire raft system with multiple raft nodes

Note: that most logic happens in RaftNode

Note: that for evaluation, we are looking for how fast it will store something and have it reflect
on every system, not how fast it will get and put (although that can be tested as well)

Note: pretty fast, look at comments in RaftNode

Note: not *true* raft since it's not true parallelism, since it uses gRPC response/request, not streaming, so some parts will be modified

Note: CAP theorem, which one? Cannot account for network partition but does everything else pretty well. Cannot match log if system comes online after shut off 
but can tolerate complete failures