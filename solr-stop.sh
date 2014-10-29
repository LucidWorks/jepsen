#!/bin/bash

export JAVA_HOME=~/jdk7
export PATH=$JAVA_HOME/bin:$PATH

cd solr-4.10.2
./bin/solr stop -all

