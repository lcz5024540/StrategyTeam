#!/usr/bin/env bash
set -Eeuo pipefail
stage=/home/ubuntu/coinstrategy-cos-archive/deploy-20261105-4fa6ccee
base=/opt/coinstrategy/15m-market-data
release=/opt/coinstrategy/15m-market-data/releases/0.1.7-realtime-candidate-4fa6ccee
old=/opt/coinstrategy/15m-market-data/releases/0.1.7-realtime-candidate-00765365
stopped=0
changed=0
restore_on_error() {
  code=$?
  trap - ERR
  if [ "$changed" = 1 ]; then
    systemctl stop coinstrategy-15m-market-data.service
    ln -s "$old" "$base/.rollback-4fa6ccee"
    mv -Tf "$base/.rollback-4fa6ccee" "$base/current"
    cp -p "$stage/data.service.before" /etc/systemd/system/coinstrategy-15m-market-data.service
    cp -p "$stage/release.env.before" /etc/coinstrategy/15m-cos-release.env
    systemctl daemon-reload
  fi
  if [ "$stopped" = 1 ]; then systemctl start coinstrategy-15m-market-data.service; fi
  systemctl start coinstrategy-15m-cos-archive.timer coinstrategy-15m-cos-release.timer
  echo "Cutover failed; attempted data-only rollback. Evidence and all source data retained." >&2
  exit "$code"
}
test "$(id -u)" = 0
python3 "$stage/check_unchanged.py"
test ! -e "$base/.next-4fa6ccee"
test ! -L "$base/.next-4fa6ccee"
test ! -e "$base/.rollback-4fa6ccee"
test ! -L "$base/.rollback-4fa6ccee"
trap restore_on_error ERR
systemctl stop coinstrategy-15m-cos-archive.timer coinstrategy-15m-cos-release.timer
# Do not interrupt an upload or its verify-before-release handoff.
idle=0
for ((attempt=0; attempt<300; attempt++)); do
  archive=$(systemctl show coinstrategy-15m-cos-archive.service -p ActiveState --value)
  maintenance=$(systemctl show coinstrategy-15m-cos-release.service -p ActiveState --value)
  if [ "$archive" = inactive ] && [ "$maintenance" = inactive ]; then idle=1; break; fi
  sleep 2
done
test "$idle" = 1
python3 "$stage/check_unchanged.py"
date --iso-8601=seconds
stopped=1
systemctl stop coinstrategy-15m-market-data.service
test "$(systemctl show coinstrategy-15m-market-data.service -p MainPID --value)" = 0
changed=1
ln -s "$release" "$base/.next-4fa6ccee"
mv -Tf "$base/.next-4fa6ccee" "$base/current"
install -m 644 -o root -g root "$release/release/cos/coinstrategy-15m-market-data.service" /etc/systemd/system/coinstrategy-15m-market-data.service
install -m 640 -o ubuntu -g ubuntu "$stage/15m-cos-release.env" /etc/coinstrategy/15m-cos-release.env
systemctl daemon-reload
systemctl start coinstrategy-15m-market-data.service
systemctl start coinstrategy-15m-cos-archive.timer coinstrategy-15m-cos-release.timer
systemctl is-active --quiet coinstrategy-15m-market-data.service
python3 "$stage/check_unchanged.py" --after
trap - ERR
date --iso-8601=seconds
