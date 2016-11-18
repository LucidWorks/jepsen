#!/bin/bash

declare -a solr_clients=("create-set-client" "cas-set-client")
declare -a solr_nemesis=("bridge" "partition-random-halves" "partition-halves")

source config.sh

for k in 1 2 3 4 5
do
    scp config.sh root@n$k:~/.
    ssh root@n$k 'bash config.sh ' $SOLR_DIR $ZK_HOME
done

echo 'Copying over latest version of scripts to remote hosts'
for k in 1 2 3 4 5
do
    scp zk*.sh root@n$k:~/.
    scp solr*.sh root@n$k:~/.
    scp delete-logs.sh root@n$k:~/.
done

echo 'Killing solr on all remote hosts'
for k in 1 2 3 4 5
do
    ssh root@n$k 'bash solr-stop.sh'
done

echo 'Killing zk on all remote hosts and delete existing solr+zk logs'
for k in 1 2 3 4 5
do
    ssh root@n$k 'bash zk-stop.sh'
    ssh root@n$k 'bash delete-logs.sh'
done

current_time=$(date "+%Y.%m.%d-%H.%M.%S")
parent_dir=~/test-results/jepsen-solr.$current_time

for i in "${solr_clients[@]}"
do
    for j in "${solr_nemesis[@]}"
    do
        echo 'Starting up zk on all remote hosts'
        for k in 1 2 3 4 5
        do
            ssh root@n$k 'bash zk-start.sh'
        done

        echo 'Starting up solr on all remote hosts'
        for k in 1 2 3 4 5
        do
            ssh root@n$k 'bash solr-start.sh'
        done

        echo 'Sleeping for 10 seconds for solrcloud cluster to come up fully'
        sleep 10

    echo 'Deleting existing jepsen5x3 collection'
        curl 'http://n1:8983/solr/admin/collections?action=delete&name=jepsen5x3&wt=json&indent=on'
    sleep 5
    ssh root@n1 '/root/solr/server/scripts/cloud-scripts/zkcli.sh -cmd upconfig -zkhost n1:2181 -confname jepsen -confdir /root/solr/server/solr/configsets/data_driven_schema_configs/conf'
    echo 'Creating new jepsen5x3 collection'
    curl 'http://n1:8983/solr/admin/collections?action=create&name=jepsen5x3&numShards=5&replicationFactor=3&maxShardsPerNode=10&wt=json&collection.configName=jepsen&indent=on&createNodeSet=n1:8983_solr,n2:8983_solr,n3:8983_solr,n4:8983_solr,n5:8983_solr'
    sleep 10
        jepsen_results_file=solrcloud_5zk_5x3_"$i"_"$j".txt

        echo 'Executing tests now: writing to' "$jepsen_results_file"


        export JVM_OPTS="-Djepsen.solr.client=$i -Djepsen.solr.nemesis=$j"
        lein test jepsen.system.solr-test 2>&1 | tee $jepsen_results_file

        echo 'Killing solr on all remote hosts'
        for k in 1 2 3 4 5
        do
            ssh root@n$k 'bash solr-stop.sh'
        done

        echo 'Killing zk on all remote hosts'
        for k in 1 2 3 4 5
        do
            ssh root@n$k 'bash zk-stop.sh'
        done

        echo 'Collecting logs from all hosts'
        results_dir=$parent_dir'/solrcloud_5zk_5x3_'"$i"_"$j"
        mkdir -p $results_dir
        here_dir=`pwd`
        cp $jepsen_results_file $results_dir
        cd $results_dir
        for k in 1 2 3 4 5
        do
                scp root@n$k:~/$SOLR_DIR/server/logs/* .
                scp root@n$k:~/$ZK_HOME/zookeeper.out .
                ssh root@n$k 'bash delete-logs.sh'
                mv solr_gc.log n$k-solr-gc.log
                mv solr.log n$k-solr.log
                mv zookeeper.out n$k-zookeeper.out
                mv solr-8983-console.log n$k-solr-console.log
        done
        cd $here_dir
    done
done