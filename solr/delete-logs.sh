#!/bin/bash

source config.sh

rm $ZK_HOME/zookeeper.out
rm $SOLR_DIR/server/logs/*
rm -rf $SOLR_DIR/server/solr/jepsen*

