#!/usr/bin/env bash
# The phone app's TV guide: an XMLTV file served from this computer (adb reverse) gives the "now" line under the
# matching channel; a second source that matches none of the channels is dropped.
source "$(dirname "$0")/../lib.sh"
scenario phone "TV guide from XMLTV"
PORT=18765
SERVE="$(mktemp -d)"
cleanup() { [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null; adbp reverse --remove "tcp:$PORT" >/dev/null 2>&1; rm -rf "$SERVE"; }
trap cleanup EXIT

# Programmes around the current time, so the "now" line is known in advance.
python3 -I - "$SERVE" <<'PY'
import sys, datetime
out = sys.argv[1]
now = datetime.datetime.now(datetime.timezone.utc).replace(minute=0, second=0, microsecond=0)
fmt = lambda t: t.strftime('%Y%m%d%H%M%S +0000')
def guide(channel, name, titles):
    rows = ''.join(f'<programme start="{fmt(now + datetime.timedelta(hours=h))}" stop="{fmt(now + datetime.timedelta(hours=h + 1))}" channel="{channel}"><title>{t}</title></programme>'
                   for h, t in titles)
    return f'<?xml version="1.0" encoding="UTF-8"?><tv><channel id="{channel}"><display-name>{name}</display-name></channel>{rows}</tv>'
open(f'{out}/guide.xml', 'w').write(guide('alpha.test', 'Alpha News', [(-1, 'Alpha earlier'), (0, 'Alpha live'), (1, 'Alpha later')]))
open(f'{out}/nomatch.xml', 'w').write(guide('nobody.test', 'Nobody', [(0, 'Nothing')]))
PY
python3 -I -m http.server "$PORT" --bind 127.0.0.1 --directory "$SERVE" >/dev/null 2>&1 &
SERVER_PID=$!
phone_reset
adbp reverse "tcp:$PORT" "tcp:$PORT" >/dev/null || die "adb reverse failed"
put_file adbp iptv_phone.json '[{"name":"Alpha News","url":"http://127.0.0.1:18765/alpha.m3u8","tvgId":"alpha.test"},
 {"name":"Beta Kids","url":"http://127.0.0.1:18765/beta.m3u8"}]' || die "cannot write the channel store"
put_file adbp epg_phone.json '{"at":0,"tried":0,"sources":["http://127.0.0.1:18765/guide.xml","http://127.0.0.1:18765/nomatch.xml"],"programmes":{}}' \
  || die "cannot write the guide"
phone_start

wait_for "the list shows Alpha News" 20 phone_has '^Alpha News$'
wait_for "Alpha News shows what is on now" 60 phone_has '^(Now|Teraz): Alpha live$'
check "Beta Kids has no guide" phone_lacks '(Now|Teraz): .*Nothing'
sources_left() { [[ "$(adbp shell run-as "$APP_PKG" cat files/epg_phone.json | json_get 'd["sources"]')" == '["http://127.0.0.1:18765/guide.xml"]' ]]; }
wait_for "the source with no matching channel is dropped" 20 sources_left
pass
