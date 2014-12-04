#!/bin/bash

export JAVA_HOME=~/jdk1.8.0_25
export PATH=$JAVA_HOME/bin:$PATH

cd zookeeper-3.4.6
./bin/zkServer.sh stop

