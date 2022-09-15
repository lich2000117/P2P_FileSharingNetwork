## Current Progress

Finally! Managed to did it.

- Drawbacks: need large socket timeout limit
- Adavantage: 
  1. can recover from any peer failure when downloading.
  2. For `maxPeer`, is the maximum concurrent connection user defined.
  3. if `maxPeer` == 3, we make 3 connections to 3 peers, then
  4. send ONLY one blockRequest for each connection, to each 3 peer, check peer failure,
     1. if peer fails, we remove from our 3 connections.
  5. get a single blockReply for each connection from 3 peers and pass them into a queue `singleBlockWriter`
     1. in the background, our new thread, `WriteBlockThread` will write all incoming blocks to local.
  6. at this stage, we finish 1. send request, 2. receive block reply, 3. have an ongoing thread writing blocks.
  7. wait untill write thread finish.
  8. check if we have all files
  9. if we still miss files (including broken connection with previous peers)
  10. go back to step 3:
      1. if we lost some connections before:
         1. (make connection to idx server)
         2. query and update resources (to get update if peer changes)
         3. go back to step 3, use new resources, make more connections
      2. if we not lose any connections, go back to step 3, do not update sources list,
      3. continue send request for next block. (step 3 onwards)
  
### Next:
- solve goodbye message problem.
- maybe buffer size reduce.

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
6. Multiple blocks download from same peer (Done).
7. Use extra Thread for peer download request, that enables client GUI run even when downloading (Done)
8. Share, search, drop From peer Code clean up. (Done)
9. Download From peer Code clean up.

## Current Logic
- Peer always has an upload thread running in background, taking connections, and providing asked block
- Peer when request block, it initialises a download thread in background, make multiple connections (default 4, need to pass as configurable parameter), 
store reader and writer into array, send request in every reader. read from all reader to download.
- Every time a note times out, program ignore it in current request-download progress,
but after each progress, program check if there are additional resources to fill in the blank where
that broken node left. If no more resources, it won't allocate more, but the program still runs in current number
of activated peers, if all peers dropped connection, download fail.

## Issues
1. Create FileMgr(filepath) not working for large file (>16MB) (Solved)
2. If peer shutdown during upload/download, program crash. It need to switch to next peer.

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

### Why need Timeout?
to make sure we do enough calculation, processes before we connect to other peers, save public resources.

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