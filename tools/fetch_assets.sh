#!/usr/bin/env bash
# Downloads assets listed in assets.conf into the APK (app/src/main/assets/dl/) at BUILD time.
CONF=app/src/main/assets/assets.conf
OUT=app/src/main/assets/dl
mkdir -p "$OUT"
BASE=""
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    ''|\#*) continue ;;
    @base=*) BASE="${line#@base=}"; continue ;;
  esac
  key="${line%%=*}"; val="${line#*=}"
  if [ -z "$key" ] || [ -z "$val" ]; then continue; fi
  case "$val" in
    http*) url="$val" ;;
    *) if [ -z "$BASE" ]; then continue; fi; url="${BASE%/}/$val" ;;
  esac
  echo "Downloading $key <- $url"
  curl -fsSL --retry 2 --max-time 40 --max-filesize 2000000 -o "$OUT/$key" "$url" \
    || { echo "WARN: failed $key (built-in fallback will be used)"; rm -f "$OUT/$key"; }
done < "$CONF"
ls -la "$OUT" || true
