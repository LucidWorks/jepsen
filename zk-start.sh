#!/bin/bash

export JAVA_HOME=~/jdk7
export PATH=$JAVA_HOME/bin:$PATH

cd zookeeper-3.4.6
./bin/zkServer.sh start
