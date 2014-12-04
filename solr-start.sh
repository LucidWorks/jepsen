#!/bin/bash

export JAVA_HOME=~/jdk1.8.0_25
export PATH=$JAVA_HOME/bin:$PATH

cd solr-4.10.2

./bin/solr -c -z n1:2181,n2:2181,n3:2181,n4:2181,n5:2181/jepsen -m 1024M -h `hostname`
