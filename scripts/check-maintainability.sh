#!/usr/bin/env bash
set -euo pipefail

violations=()
while IFS= read -r -d '' file; do
  name="${file##*/}"
  limit=1800
  case "$name" in
    MainActivity.kt) limit=900 ;;
    TcptunVpnService.kt) limit=3500 ;;
  esac
  lines=$(wc -l < "$file")
  if (( lines > limit )); then
    violations+=("$file has $lines lines (limit $limit)")
  fi
done < <(find app/src/main/java -type f -name '*.kt' -print0)

service_file="app/src/main/java/com/gostartkit/tcptun/TcptunVpnService.kt"
max_service_function_lines=180
while IFS='|' read -r signature lines; do
  if (( lines > max_service_function_lines )); then
    violations+=(
      "$service_file function '$signature' has $lines lines (limit $max_service_function_lines)"
    )
  fi
done < <(
  awk '
    function emit(end_line) {
      if (start_line > 0) print signature "|" end_line - start_line + 1
    }
    /^    ((private|override) )?(inline )?fun / {
      emit(NR - 1)
      start_line = NR
      signature = $0
      sub(/^ +/, "", signature)
      next
    }
    /^    companion object \{/ {
      emit(NR - 1)
      start_line = 0
      signature = ""
    }
    END { emit(NR) }
  ' "$service_file"
)

if (( ${#violations[@]} > 0 )); then
  printf 'Maintainability limits exceeded:\n' >&2
  printf '%s\n' "${violations[@]}" >&2
  exit 1
fi
