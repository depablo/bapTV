#!/usr/bin/env bash
# The phone app's channel list from a known store: a user label replaces the playlist name, hidden channels stay
# out, iptv-org's "Undefined" group is not shown; search narrows the list and says when nothing matches.
source "$(dirname "$0")/../lib.sh"
scenario phone "channel list and search"
phone_reset
put_file adbp iptv_phone.json '[
 {"name":"Alpha News","url":"http://127.0.0.1:18765/alpha.m3u8","group":"News"},
 {"name":"Beta Kids","url":"http://127.0.0.1:18765/beta.m3u8","group":"Undefined"},
 {"name":"Gamma Movies","url":"http://127.0.0.1:18765/gamma.m3u8","group":"Movies","label":"My Gamma"},
 {"name":"Delta Hidden","url":"http://127.0.0.1:18765/delta.m3u8","hidden":true}]' || die "cannot write the channel store"
phone_start

wait_for "the list shows Alpha News" 20 phone_has '^Alpha News$'
check "the playlist group is shown" phone_has '^News$'
check "the user's label replaces the playlist name" phone_has '^My Gamma$'
check "a hidden channel stays out" phone_lacks '^Delta Hidden$'
check "\"Undefined\" is not a category" phone_lacks '^Undefined$'

search_focused() { adbp shell dumpsys input_method | tr -d '\r' | grep -q 'mInputShown=true'; }
check "the search field takes the focus" phone_tap_until "resource-id=\"$APP_PKG:id/phone_search\"" search_focused
adbp shell input text zzqx
wait_for "nothing matches 'zzqx'" 10 phone_has 'No channel matches|Żaden kanał nie pasuje'
adbp shell input keyevent KEYCODE_MOVE_END; for _ in 1 2 3 4 5 6; do adbp shell input keyevent KEYCODE_DEL; done
adbp shell input text beta
wait_for "'beta' finds Beta Kids" 10 phone_has '^Beta Kids$'
check "and only it" phone_lacks '^Alpha News$'
adbp shell input keyevent KEYCODE_BACK
pass
