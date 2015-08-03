#!/bin/bash

declare -a solr_clients=("create-set-client" "cas-set-client")
declare -a solr_nemesis=("bridge" "partition-random-halves" "partition-halves")

source setup_env.sh

for k in 1 2 3 4 5
do
    sshpass -pubuntu scp setup_env.sh ubuntu@n$k:~/.
	sshpass -pubuntu ssh ubuntu@n$k 'bash setup_env.sh ' $SOLR_DIR $ZK_HOME
done


function upload_local_build() {
    LOCAL_BUILD="$1"
    if [ -z "$LOCAL_BUILD" ]; then
        echo -e 'No local build specified, assuming ' $SOLR_DIR
    else
        echo -e 'Local build specified: ' $LOCAL_BUILD

        full_filename=$(basename "$LOCAL_BUILD")
        extension="${full_filename##*.}"
        filename="${full_filename%.*}"

        if [ -e "$LOCAL_BUILD" ]; then
            for k in 1 2 3 4 5
            do
                echo -e 'Uploading file ' $LOCAL_BUILD ' to n' $k
                sshpass -pubuntu scp $LOCAL_BUILD ubuntu@n$k:~/.
                echo -e 'Deleting old solr home at ' $SOLR_DIR
                sshpass -pubuntu ssh ubuntu@n$k 'rm -r' $SOLR_DIR
                echo -e 'Extracting artifact ' $full_filename
                sshpass -pubuntu ssh ubuntu@n$k 'tar xvf ' $full_filename
                echo -e 'Renaming ' $filename ' directory to ' $SOLR_DIR
                sshpass -pubuntu ssh ubuntu@n$k 'mv ' $filename ' ' $SOLR_DIR
            done
        else
            echo -e 'Local build does not exist at ' $LOCAL_BUILD
            exit 1
        fi
    fi
} # end upload_local_build

echo 'Copying over latest version of scripts to remote hosts'
for k in 1 2 3 4 5
do
        sshpass -pubuntu scp zk*.sh ubuntu@n$k:~/.
	sshpass -pubuntu scp solr*.sh ubuntu@n$k:~/.
	sshpass -pubuntu scp delete-logs.sh ubuntu@n$k:~/.
done

echo 'Killing solr on all remote hosts'
for k in 1 2 3 4 5
do
    	sshpass -pubuntu ssh ubuntu@n$k 'bash solr-stop.sh'
done

echo 'Killing zk on all remote hosts and delete existing solr+zk logs'
for k in 1 2 3 4 5
do
	    sshpass -pubuntu ssh ubuntu@n$k 'bash zk-stop.sh'
      sshpass -pubuntu ssh ubuntu@n$k 'bash delete-logs.sh'
done

upload_local_build $1

current_time=$(date "+%Y.%m.%d-%H.%M.%S")
parent_dir=~/test-results/jepsen-solr.$current_time

for i in "${solr_clients[@]}"
do
	for j in "${solr_nemesis[@]}"
	do
		echo 'Starting up zk on all remote hosts'
		for k in 1 2 3 4 5
		do
			sshpass -pubuntu ssh ubuntu@n$k 'bash zk-start.sh'
		done

		echo 'Starting up solr on all remote hosts'
		for k in 1 2 3 4 5
		do
			sshpass -pubuntu ssh ubuntu@n$k 'bash solr-start.sh'
		done

		echo 'Sleeping for 10 seconds for solrcloud cluster to come up fully'
		sleep 10

	echo 'Deleting existing jepsen5x3 collection'
    	curl 'http://n1:8983/solr/admin/collections?action=delete&name=jepsen5x3&wt=json&indent=on'
	sleep 5
	echo 'Creating new jepsen5x3 collection'
	curl 'http://n1:8983/solr/admin/collections?action=create&name=jepsen5x3&numShards=5&replicationFactor=3&maxShardsPerNode=10&wt=json&collection.configName=jepsen&indent=on&createNodeSet=n1:8983_solr,n2:8983_solr,n3:8983_solr,n4:8983_solr,n5:8983_solr'
	sleep 10
		jepsen_results_file=solrcloud_5zk_5x3_"$i"_"$j".txt

		echo 'Executing tests now: writing to' "$jepsen_results_file"


		export JVM_OPTS="-Djepsen.solr.client=$i -Djepsen.solr.nemesis=$j"
		lein with-profile +solr test jepsen.system.solr-test 2>&1 | tee $jepsen_results_file

		echo 'Killing solr on all remote hosts'
		for k in 1 2 3 4 5
		do
			sshpass -pubuntu ssh ubuntu@n$k 'bash solr-stop.sh'
		done

		echo 'Killing zk on all remote hosts'
		for k in 1 2 3 4 5
		do
			sshpass -pubuntu ssh ubuntu@n$k 'bash zk-stop.sh'
		done

		echo 'Collecting logs from all hosts'
		results_dir=$parent_dir'/solrcloud_5zk_5x3_'"$i"_"$j"
		mkdir -p $results_dir
		here_dir=`pwd`
		cp $jepsen_results_file $results_dir
		cd $results_dir
		for k in 1 2 3 4 5
		do
		        sshpass -pubuntu scp ubuntu@n$k:~/$SOLR_DIR/server/logs/* .
		        sshpass -pubuntu scp ubuntu@n$k:~/$ZK_HOME/zookeeper.out .
                sshpass -pubuntu ssh ubuntu@n$k 'bash delete-logs.sh'
		        mv solr_gc.log n$k-solr-gc.log
		        mv solr.log n$k-solr.log
		        mv zookeeper.out n$k-zookeeper.out
		        mv solr-8983-console.log n$k-solr-console.log
		done
		cd $here_dir
	done
done