#!/usr/bin/env bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# 在本机启动一个 3 主 3 从的 Redis Cluster（127.0.0.1:7000-7005），供集群集成测试使用。
# 用法：start-redis-cluster.sh [数据目录]；随后以
#   SPRING_DATA_REDIS_CLUSTER_NODES=127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002
# 运行测试。停止：redis-cli -p 700x shutdown nosave。
set -euo pipefail

DATA_DIR="${1:-${TMPDIR:-/tmp}/cocache-redis-cluster}"
PORTS=(7000 7001 7002 7003 7004 7005)

mkdir -p "$DATA_DIR"
for port in "${PORTS[@]}"; do
  mkdir -p "$DATA_DIR/$port"
  redis-server --port "$port" --bind 127.0.0.1 --daemonize yes \
    --dir "$DATA_DIR/$port" --cluster-enabled yes --cluster-config-file "nodes-$port.conf" \
    --cluster-node-timeout 5000 --appendonly no --save '' \
    --logfile "$DATA_DIR/$port/redis.log"
done

for port in "${PORTS[@]}"; do
  until redis-cli -p "$port" ping >/dev/null 2>&1; do sleep 0.2; done
done

nodes=()
for port in "${PORTS[@]}"; do nodes+=("127.0.0.1:$port"); done
redis-cli --cluster create "${nodes[@]}" --cluster-replicas 1 --cluster-yes >/dev/null

until redis-cli -p 7000 cluster info | grep -q 'cluster_state:ok'; do sleep 0.5; done
echo "Redis Cluster ready on ${nodes[*]}"
