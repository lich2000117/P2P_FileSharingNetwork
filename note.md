## TO DO
1. get peer to peer connection working. (Done)
2. can only transfer file in base dir. cannot work in subdir.
3. Shutdown InputString and OutputString after connection
4. Shutdown each socket connection
5. Now, it only works on first block, need to iterate through every block (get needed index first).
6. How to upload/download **multiple** blocks at the same time? only one port, multiple connection to other peers?
   1. It can now download / upload at the same time with PeerIOThread.

ShareRecord stores file Descriptor, file content and sharer secret.
Therefore, use HashMap<filename, ShareRecord> to store sharer's history of shared files.

Peer asking to download: send MD5 of that file.

Peer upload node: check HashMap (MD5) == sharesecret




### Process of Connection:

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


### Code:

- `runServer.sh` : use `./runServer.sh` to run this script, clear maven package and reintall maven dependencies and 
- recompile code to run server program.
- `runClient.sh` : use `./runClient.sh` to run this script, clear maven package and reintall maven dependencies and 
- recompile code to run client program.
- `runBoth.sh` : use `./runBoth.sh` to run this script, clear maven package and reintall maven dependencies and 
- recompile code, run server and client in separate terminal windows on the same machine.

### Run Code:

#### Same Machine:
use `./runBoth.sh` to run server and client on same machine, by default:
- Server Address: localhost
- Server Port: 3200

#### Different Machine in a local network (IP address begin with 192.xxx.xxx):

- Server Machine: use `./runServer.sh` to run server, by default:
  - Server Address: localhost (use `hostname -I` in terminal to get current local IP address).
  - Server Port: 3200

- Client Machine: use `./runClient.sh` to run Client, *Need to Update Settings*:
  1. Open `Config` by pressing `C`,
  2. Update Index Server Address: enter Server Machine's local IP address retrieved from previous step.
  3. Update Port from previous step (by default 3200).
  4. Done!