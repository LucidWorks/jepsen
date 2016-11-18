#!/bin/bash

source config.sh

LOCAL_BUILD="$1"
if [ -z "$LOCAL_BUILD" ]; then
    wget http://www-us.apache.org/dist/lucene/solr/$SOLR_VERSION/solr-$SOLR_VERSION.tgz
    LOCAL_BUILD="solr-$SOLR_VERSION.tgz"
fi

echo -e 'Local build specified: ' $LOCAL_BUILD

full_filename=$(basename "$LOCAL_BUILD")
extension="${full_filename##*.}"
filename="${full_filename%.*}"

if [ -e "$LOCAL_BUILD" ]; then
    for k in 1 2 3 4 5
    do
        echo -e 'Uploading file ' $LOCAL_BUILD ' to n' $k
        scp $LOCAL_BUILD root@n$k:~/.
        echo -e 'Deleting old solr home at ' $SOLR_DIR
        ssh root@n$k 'rm -r' $SOLR_DIR
        echo -e 'Extracting artifact ' $full_filename
        ssh root@n$k 'tar xf ' $full_filename
        echo -e 'Renaming ' $filename ' directory to ' $SOLR_DIR
        ssh root@n$k 'mv ' $filename ' ' $SOLR_DIR        
    done
else
    echo -e 'Local build does not exist at ' $LOCAL_BUILD
    exit 1
fi