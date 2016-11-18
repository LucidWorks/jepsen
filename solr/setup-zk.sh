#!/bin/bash

source config.sh
	
if [ ! -f $ZK_HOME.tar.gz ]; then
	wget http://www-us.apache.org/dist/zookeeper/$ZK_HOME/$ZK_HOME.tar.gz
fi

for k in 1 2 3 4 5
do
	scp $ZK_HOME.tar.gz root@n$k:~/
	ssh root@n$k 'rm -r ' $ZK_HOME
	ssh root@n$k 'tar xf ' $ZK_HOME.tar.gz
	ssh root@n$k 'rm -f ' $ZK_HOME.tar.gz
	scp zoo.cfg root@n$k:~/$ZK_HOME/conf
	ssh root@n$k 'mkdir ' ~/zkData
	ssh root@n$k 'echo '"$k "'> ~/zkData/myid' 
done
