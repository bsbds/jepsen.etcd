#!/bin/bash


workloads=("wr" "set", "append")

nemeses=("none" "partition" "member" "partition,member")

for workload in "${workloads[@]}"; do
  for nemesis in "${nemeses[@]}"; do
    lein run test --ssh-private-key ~/.ssh/id_ed25519 --workload $workload --time-limit 300 --concurrency 2n -r 1000 --nemesis $nemesis --nemesis-interval 30 --test-count 5
  done
done
