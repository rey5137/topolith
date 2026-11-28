#!/usr/bin/env bash
set -euo pipefail
set -m

logs=$(mktemp -d)
pids=()
watchdog=''
cleanup() {
  status=$?
  trap - EXIT TERM INT
  if (( status != 0 )); then
    for log in "$logs"/*.log; do
      [[ -f "$log" ]] || continue
      echo "--- $log ---" >&2
      tail -80 "$log" >&2
    done
  fi
  for pid in "${pids[@]}"; do
    [[ -z "$pid" ]] || kill -TERM -- "-$pid" 2>/dev/null || true
  done
  [[ -z "$watchdog" ]] || kill -TERM -- "-$watchdog" 2>/dev/null || true
  wait 2>/dev/null || true
  rm -rf "$logs"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 124' TERM
trap 'exit 130' INT
( sleep 900; echo 'Getting-started check timed out' >&2; kill -TERM "$$" ) &
watchdog=$!

wait_up() {
  local pid=$1 url=$2 log=$3 application=$4 deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if ! kill -0 "$pid" 2>/dev/null; then echo "Process exited before $url was ready" >&2; return 1; fi
    if grep -q "Started $application in " "$log" &&
      curl --silent --max-time 2 --output /dev/null "$url"; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for $url" >&2
  return 1
}
wait_down() {
  local url=$1 deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if ! curl --silent --max-time 2 --output /dev/null "$url"; then return 0; fi
    sleep 1
  done
  echo "Timed out waiting for $url to stop" >&2
  return 1
}
stop_group() {
  kill -TERM -- "-$1" 2>/dev/null || true
  wait "$1" 2>/dev/null || true
}

( exec ./mvnw -q install -DskipTests ) >"$logs/install.log" 2>&1 &
pids+=("$!")
wait "${pids[0]}"
pids[0]=''
( exec ./mvnw -pl samples/hosts/monolith spring-boot:run ) >"$logs/monolith.log" 2>&1 &
pids+=("$!")
wait_up "${pids[1]}" http://localhost:8080/ "$logs/monolith.log" MonolithApplication
stop_group "${pids[1]}"
wait_down http://localhost:8080/
pids[1]=''
( exec ./mvnw -pl samples/payment/payment-service spring-boot:run ) >"$logs/payment.log" 2>&1 &
pids+=("$!")
wait_up "${pids[2]}" http://localhost:8081/payment/.topolith/api-version "$logs/payment.log" PaymentServiceApplication
( exec ./mvnw -pl samples/hosts/hybrid spring-boot:run ) >"$logs/hybrid.log" 2>&1 &
pids+=("$!")
wait_up "${pids[3]}" http://localhost:8080/ "$logs/hybrid.log" HybridApplication
curl --fail --silent --show-error --max-time 10 -H 'Content-Type: application/json' \
  -d '{"reference":"getting-started","amount":1}' http://localhost:8080/user/orders > /dev/null
echo 'Getting-started commands passed'
