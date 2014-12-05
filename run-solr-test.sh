#!/bin/bash

declare -a solr_clients=("create-set-client" "cas-set-client")
declare -a solr_nemesis=("bridge" "partition-random-halves" "partition-halves")

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

		echo 'Sleeping for 90 seconds for solrcloud cluster to come up fully'
		sleep 90

	echo 'Deleting existing jepsen5x3 collection'
    	curl 'http://n1:8983/solr/admin/collections?action=delete&name=jepsen5x3&wt=json&indent=on'
	sleep 5
	echo 'Creating new jepsen5x3 collection'
	curl 'http://n1:8983/solr/admin/collections?action=create&name=jepsen5x3&numShards=5&replicationFactor=3&maxShardsPerNode=10&wt=json&collection.configName=jepsen&indent=on'
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
		results_dir=~/temp/jepsen-results2/solrcloud_5zk_5x3_"$i"_"$j"
		mkdir -p $results_dir
		here_dir=`pwd`
		cp $jepsen_results_file $results_dir
		cd $results_dir
		for k in 1 2 3 4 5
		do
		        sshpass -pubuntu scp ubuntu@n$k:~/solr-4.10.2/example/logs/* .
		        sshpass -pubuntu scp ubuntu@n$k:~/zookeeper-3.4.6/zookeeper.out .
			      sshpass -pubuntu ssh ubuntu@n$k 'bash delete-logs.sh'
		        mv solr_gc.log n$k-solr-gc.log
		        mv solr.log n$k-solr.log
		        mv zookeeper.out n$k-zookeeper.out
		        mv solr-8983-console.log n$k-solr-console.log
		done
		cd $here_dir
	done
done
