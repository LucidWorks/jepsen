#!/bin/bash

source setup_env.sh

rm $ZK_HOME/zookeeper.out
rm $SOLR_DIR/server/logs/*
rm -rf $SOLR_DIR/server/solr/jepsen*

