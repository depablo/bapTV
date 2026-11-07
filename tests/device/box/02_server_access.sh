#!/usr/bin/env bash
# Protection of the debug server and the phone remote server: token, Host header, cross-site requests, pairing.
source "$(dirname "$0")/../lib.sh"
scenario box "server access rules"
code_is() { [[ "$1" == "$2" ]]; }

check "/status is open" code_is "$(api_code GET /status)" 200
check "/key needs the token" code_is "$(api_code POST '/key?name=UP')" 401
check "/ui needs the token" code_is "$(api_code GET /ui)" 401
check "a wrong token is refused" code_is "$(api_code GET /ui -H 'X-Tv-Token: 000000')" 401
check "the token opens /ui" code_is "$(api_code GET /ui -H "X-Tv-Token: $TOKEN")" 200
check "an unknown path is 404" code_is "$(api_code GET /no-such-path)" 404
check "a host name is refused (DNS rebinding)" code_is "$(api_code GET /status -H 'Host: tv.example.com')" 403
check "a cross-site request is refused" code_is "$(api_code POST '/key?name=UP' -H "X-Tv-Token: $TOKEN" -H 'Origin: http://evil.example' -H 'Sec-Fetch-Site: cross-site')" 403

check "the remote's API needs pairing" code_is "$(remote GET /api/channels -o /dev/null -w '%{http_code}')" 401
check "a wrong pairing code is refused" code_is "$(remote GET '/pair?code=0000' -o /dev/null -w '%{redirect_url}' | sed 's|.*/?|?|')" '?pair=failed'
check "the pairing status of an unknown request is 'expired'" code_is "$(remote GET '/pair/status?id=00' | json_get 'd["state"]')" expired
pass
