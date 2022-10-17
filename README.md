# P2P File Sharing Application

A file sharing P2P solution using pure Socket and Java.

You can transfer files between Virtual Machine and Host in no time.


# How to run:

```
bash ./runServer.sh
bash ./runClient.sh
```
OR (replace server ip and client IP)
```
mvn clean
mvn package
mvn assembly:single
```

Client:
```
java -cp target/idxsrv-0.0.1-SNAPSHOT-jar-with-dependencies.jar comp90015.idxsrv.Filesharer -sa 10.0.2.14
```

Server: 
```
java -cp target/idxsrv-0.0.1-SNAPSHOT-jar-with-dependencies.jar comp90015.idxsrv.IdxSrv

```
