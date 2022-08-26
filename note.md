Process of Connection:

Peer: 
1. Create Socket with Server
2. Establish communication channel (input and output stream)
3. send an authenticated message (class message -> authenticateMessage) using a secret.
4. send an actual request from class message.

Server:
1. While loop keep listening
2. if there's a connection from a socket, establish connection by
3. calling a process socket (request) method
4. after finish processing, close connection.
5. keep listening, go back to step 1.