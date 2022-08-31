## TO DO
1. use `getLocalTempFile` to load/create a downloading local file,
   - then, get needed blocks into an array `neededBlocks`,
   - then, for every available peer, try download all `neededBlocks`, catch exception: 1. Block not available 2. Connection lost
   - if Block unavailable exception, put current index back, try next block
   - if iterate all block, still need some, skip to next peer (with updated `neededBlocks`).
   - if Connection exception is catched, skip to next peer (with current index)
2. Memory Issue, after sharing/downloading big files, those files are still occupied
3. Concurrent Sharing/Downloading (Done)
4. If a peer cannot be connected, move on to next (Done)
5. If a block is not available (peer stopped sharing, move on to next) (Done)

## Issues
1. Create FileMgr(filepath) not working for large file (>16MB)

## Big To DO
1. get peer to peer connection working. (Done)
2. can only transfer file in base dir. cannot work in subdir. (now it creates and goes to `DOWNLOAD/` folder) (Done)
3. Shutdown InputString and OutputString after connection
4. Shutdown each socket connection
5. Now, it only works on small files (16MB), only transfer first block, need to iterate through every block (get needed index first, then iterate to get all).
6. How to upload/download **multiple** blocks at the same time? only one port, multiple connection to other peers?
   1. It can now download / upload at the same time with PeerIOThread.
7. Base64 binary file transmission. (Done)

ShareRecord stores file Descriptor, file content and sharer secret.
Therefore, use HashMap<filename, ShareRecord> to store sharer's history of shared files.

Peer asking to download: send MD5 of that file.

Peer upload node: check HashMap (MD5) == sharesecret




### Process of Connection:

#### VirtualBox Multiple VMs network set up:
https://www.youtube.com/watch?v=vReAkOq-59I

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


### Script:
- `copyToSFs.bat` Windows: copy current project to replace all share folders project file (distributed node for testing use different share folders.)
- `copyToSFs.sh` Linux: copy current project to replace all share folders project file (distributed node for testing use different share folders.)
- `cleanDownload.sh` : remove DOWNLOAD folder from download.
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