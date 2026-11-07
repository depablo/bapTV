# Pairing-on-the-TV steps shared by emu/06 and box/04 (sourced after the player and its phone remote server run).
# The phone's side is played by curl: POST /pair/request, then GET /pair/status.

remote_up() { [[ "$(remote GET / -o /dev/null -w '%{http_code}')" == 200 ]]; }
wait_for "the phone remote server answers" 30 remote_up

pair_request() { remote POST "/pair/request?name=$1" | json_get 'd.get("id","")'; }
pair_state() { remote GET "/pair/status?id=$1" | json_get 'd.get("state")'; }
is_state() { [[ "$(pair_state "$1")" == "$2" ]]; }

wait_for "the player is on screen" 30 ui_activity_is MainActivity
ID="$(pair_request test-reject)"
check "the box takes the request" test -n "$ID"
wait_for "the TV asks 'Pair a phone?'" 15 ui_dialog_has 'Pair a phone\?|Sparować telefon\?'
check "the question names the phone" ui_dialog_has 'test-reject'
check "Reject is focused" ui_focused '^(Reject|Odrzuć)$'
check "a second request waits its turn" test -z "$(pair_request test-second)"
check "the phone waits for the answer" is_state "$ID" waiting
key OK
wait_for "rejecting answers the phone" 10 is_state "$ID" rejected
check "the answer is given once" is_state "$ID" expired

ID="$(pair_request test-accept)"
check "a new request is taken" test -n "$ID"
wait_for "the TV asks again" 15 ui_dialog_has 'test-accept'
key RIGHT; key OK
accepted_token() { STATUS="$(remote GET "/pair/status?id=$ID")"; [[ "$(json_get 'd.get("state")' <<<"$STATUS")" == accepted ]]; }
wait_for "accepting answers the phone" 10 accepted_token
PAIR_TOKEN="$(json_get 'd.get("token","")' <<<"$STATUS")"
check "the answer carries a token" test -n "$PAIR_TOKEN"
api_ok_with() { [[ "$(remote GET /api/channels -o /dev/null -w '%{http_code}' -H "Cookie: baptv_remote=$1")" == "$2" ]]; }
check "the token opens the remote's API" api_ok_with "$PAIR_TOKEN" 200
check "without it the API refuses" api_ok_with "" 401
