#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: purchased_1m_remote_task.sh start|status|log REMOTE_ROOT TASK_ID [python-args...]" >&2
  exit 2
fi

command="$1"
remote_root="$2"
task_id="$3"
shift 3

if [[ ! "$remote_root" =~ ^[A-Za-z0-9._/-]+$ ]] || [[ "$remote_root" == /* ]] || [[ "$remote_root" == *".."* ]]; then
  echo "invalid remote root: $remote_root" >&2
  exit 2
fi
if [[ ! "$task_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "invalid task id: $task_id" >&2
  exit 2
fi

root="$HOME/$remote_root"
runner="$root/purchased_1m_bronze_remote.sh"
script="$root/purchased_1m_bronze.py"
task_root="$HOME/.cache/digital-person-purchased-1m-bronze/tasks/${remote_root##*/}/$task_id"
pid_file="$task_root/pid"
exit_file="$task_root/exit_code"
log_file="$task_root/run.log"
run_file="$task_root/run.sh"

is_running() {
  [ -f "$pid_file" ] || return 1
  local pid
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

print_last_log_line() {
  if [ -s "$log_file" ]; then
    tail -n 1 "$log_file" | sed 's/^/LOG /'
  fi
}

case "$command" in
  start)
    [ "$#" -gt 0 ] || { echo "start requires Python arguments" >&2; exit 2; }
    [ -x "$runner" ] && [ -r "$script" ] || {
      echo "Bronze remote source is incomplete under $root" >&2
      exit 2
    }
    mkdir -p "$task_root"
    if [ -f "$exit_file" ]; then
      previous_rc="$(cat "$exit_file" 2>/dev/null || true)"
      if [ "$previous_rc" = "0" ]; then
        echo "DONE 0"
        print_last_log_line
        exit 0
      fi
      stamp="$(date -u +%Y%m%dT%H%M%SZ)"
      [ ! -f "$log_file" ] || mv "$log_file" "$task_root/run.$stamp.log"
      rm -f "$exit_file" "$pid_file"
    elif is_running; then
      echo "RUNNING"
      print_last_log_line
      exit 0
    else
      rm -f "$pid_file"
    fi

    cat > "$run_file" <<'RUNNER'
#!/usr/bin/env bash
set +e
runner="$1"
script="$2"
log_file="$3"
exit_file="$4"
shift 4
bash "$runner" "$script" "$@" >"$log_file" 2>&1
rc=$?
tmp_exit="${exit_file}.tmp.$$"
printf '%s\n' "$rc" > "$tmp_exit"
mv "$tmp_exit" "$exit_file"
exit "$rc"
RUNNER
    chmod 700 "$run_file"
    nohup bash "$run_file" "$runner" "$script" "$log_file" "$exit_file" "$@" \
      </dev/null >/dev/null 2>&1 &
    pid="$!"
    printf '%s\n' "$pid" > "$pid_file"
    echo "STARTED $pid"
    ;;
  status)
    if [ -f "$exit_file" ]; then
      rc="$(cat "$exit_file")"
      [[ "$rc" =~ ^[0-9]+$ ]] || { echo "invalid remote exit code: $rc" >&2; exit 3; }
      echo "DONE $rc"
      print_last_log_line
      exit 0
    fi
    if is_running; then
      echo "RUNNING"
      print_last_log_line
      exit 0
    fi
    echo "LOST"
    print_last_log_line
    exit 3
    ;;
  log)
    [ -f "$log_file" ] && cat "$log_file"
    ;;
  *)
    echo "unsupported task command: $command" >&2
    exit 2
    ;;
esac
