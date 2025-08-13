#!/bin/bash

# Distributed Mutual Exclusion Process Runner
# Usage: ./run.sh <process_id> [--dme] [--no-dme]

if [ $# -lt 1 ]; then
    echo "Usage: $0 <process_id> [--dme|--no-dme]"
    echo "Example: $0 1 --dme"
    echo "Example: $0 2 --no-dme"
    exit 1
fi

PROCESS_ID=$1
USE_DME="true"

# Parse DME flag
if [ "$2" = "--no-dme" ]; then
    USE_DME="false"
elif [ "$2" = "--dme" ]; then
    USE_DME="true"
fi

# Configuration - Modify these as needed
PEER1_HOST="localhost"
PEER1_PORT=8001
PEER2_HOST="localhost"
PEER2_PORT=8002
PEER3_HOST="localhost"
PEER3_PORT=8003

# Create src directory if it doesn't exist
mkdir -p src

echo "=== Distributed Mutual Exclusion Process Runner ==="
echo "Process ID: $PROCESS_ID"
echo "DME Enabled: $USE_DME"
echo ""

# Clean and compile
echo "Compiling Java source..."
javac -d . src/Process.java

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

# Clean the shared file only for process 1 to avoid race conditions
if [ "$PROCESS_ID" = "1" ]; then
    echo "Cleaning shared file..."
    rm -f shared_file.txt
    touch shared_file.txt
fi

echo "Starting process $PROCESS_ID..."

# Build peer arguments based on current process
PEERS=""
case $PROCESS_ID in
    1)
        PEERS="2:$PEER2_HOST:$PEER2_PORT 3:$PEER3_HOST:$PEER3_PORT"
        ;;
    2)
        PEERS="1:$PEER1_HOST:$PEER1_PORT 3:$PEER3_HOST:$PEER3_PORT"
        ;;
    3)
        PEERS="1:$PEER1_HOST:$PEER1_PORT 2:$PEER2_HOST:$PEER2_PORT"
        ;;
    *)
        echo "Invalid process ID. Use 1, 2, or 3."
        exit 1
        ;;
esac

echo "Peer configuration: $PEERS"
echo ""

# Start the Java process
java src/Process $PROCESS_ID $USE_DME $PEERS
