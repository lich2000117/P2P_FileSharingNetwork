#!/bin/bash
mvn clean;
mvn package;
mvn assembly:single;
java -cp target/idxsrv-0.0.1-SNAPSHOT-jar-with-dependencies.jar comp90015.idxsrv.Filesharer -sa 10.0.2.9
