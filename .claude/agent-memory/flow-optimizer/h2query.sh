#!/bin/bash
# Helper: query active H2 DB via AUTO_SERVER
H2_JAR="/c/Users/Root/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.2.224/7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad/h2-2.2.224.jar"
DB_URL="jdbc:h2:file:./.workflow/workflow-db;AUTO_SERVER=TRUE;IFEXISTS=TRUE"

if [ -z "$1" ]; then
  echo "Usage: $0 \"SQL QUERY\""
  exit 1
fi

java -cp "$H2_JAR" org.h2.tools.Shell -url "$DB_URL" -user sa -password "" -sql "$1"
