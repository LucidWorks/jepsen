(defproject solr "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "3.1.0" :exclusions [commons-io]]
                 [cheshire "5.6.2"] 
                 [jepsen "0.1.3-SNAPSHOT"]
                 [com.codesignals/flux "0.5.0"
                   :exclusions [org.apache.httpcomponents/httpclient commons-fileupload commons-io org.slf4j/slf4j-log4j12]]]
  :jvm-opts ["-Xmx32g"
             "-XX:+UseConcMarkSweepGC"
             "-XX:+UseParNewGC"
             "-XX:+CMSParallelRemarkEnabled"
             "-XX:+AggressiveOpts"
             "-XX:+UseFastAccessorMethods"
             "-XX:-OmitStackTraceInFastThrow"
             "-XX:MaxInlineLevel=32"
             "-XX:MaxRecursiveInlineLevel=2"
             "-server"])

