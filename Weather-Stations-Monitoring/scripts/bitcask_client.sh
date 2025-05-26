#!/bin/bash

BASE_URL="http://localhost:8080/view"

function view_all() {
    timestamp=$(date +%s)
    output_file="${timestamp}.csv"
    response=$(curl -s "$BASE_URL")

    if [ $? -ne 0 ]; then
        echo "Error: Failed to connect to Central Station"
        exit 1
    fi

    echo "$response" > "$output_file"
    echo "Saved all data to $output_file"
}

function view_key() {
    key="$1"
    response=$(curl -s "$BASE_URL?key=$key")

    if [ $? -ne 0 ]; then
        echo "Error: Failed to connect to Central Station"
        exit 1
    fi

    if [ -z "$response" ]; then
        echo "No data found for key: $key"
    else
        echo "$response"
    fi
}

function perf_test() {
    clients="$1"
    echo "Starting performance test with $clients clients..."

    # Create dedicated folder with timestamp
    test_timestamp=$(date +%s)
    perf_dir="ClientThreads_${test_timestamp}"
    mkdir -p "$perf_dir"

    echo "Created results directory: $perf_dir"

    for ((i=1; i<=$clients; i++)); do
        timestamp=$(date +%s)
        output_file="${perf_dir}/${timestamp}_thread_${i}.csv"

        curl -s "$BASE_URL" > "$output_file" &
    done

    wait
    echo "Performance test completed. Results saved to timestamped CSV files."
}

# Parse arguments
case "$1" in
    --view-all)
        view_all
        ;;
    --view)
        if [ -z "$2" ]; then
            echo "Error: Missing key parameter"
            echo "Usage: $0 --view --key=KEY"
            exit 1
        fi
        key="${2#--key=}"
        view_key "$key"
        ;;
    --perf)
        if [ -z "$2" ]; then
            echo "Error: Missing clients parameter"
            echo "Usage: $0 --perf --clients=N"
            exit 1
        fi
        clients="${2#--clients=}"
        if ! [[ "$clients" =~ ^[0-9]+$ ]]; then
            echo "Error: Clients must be a number"
            exit 1
        fi
        perf_test "$clients"
        ;;
    *)
        echo "Usage:"
        echo "  $0 --view-all                     View all keys and values"
        echo "  $0 --view --key=KEY               View value for specific key"
        echo "  $0 --perf --clients=N             Run performance test with N clients"
        exit 1
        ;;
esac

exit 0
