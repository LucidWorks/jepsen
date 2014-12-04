#!/bin/bash

export JAVA_HOME=~/jdk1.8.0_25
export PATH=$JAVA_HOME/bin:$PATH

cd solr-4.10.2
./bin/solr stop -all

