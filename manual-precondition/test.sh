#!/bin/sh
# Off-car test of the manual-precondition mod with the HMG-API mod SDK (after
# mods/examples/hello/test.sh in HMG-API). HMG-API's mod workflow runs it with HMGAPI_SDK
# (the unpacked SDK) and HMGAPI_MOD_BUILD (this mod as bin/build-mod.sh built it):
#   HMGAPI_SDK=<sdk> HMGAPI_MOD_BUILD=<built mod> sh manual-precondition/test.sh
# What it checks: the APK holds the app and HMG-API's client; HMG-API installs, plans, applies
# and removes the mod on a US Ioniq 6 260903; it refuses to install it on a car that isn't an
# E-GMP EV. A mod with no patches.json is never planned by the patch engine, so its mod.json
# "system" is checked at `engine install` (and by the mod loader on the unit), not by `plan`.
set -e
B=${HMGAPI_MOD_BUILD:?set HMGAPI_MOD_BUILD to the built mod (the out dir of bin/build-mod.sh)}
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked mod SDK}/test/lib.sh"

APK=$B/app/ManualPrecondition.apk
check "built: the APK is there" yes "$([ -f "$APK" ] && echo yes)"
unzip -p "$APK" classes.dex > $T/classes.dex
for c in dev/manualprecond/MainActivity dev/manualprecond/ToggleReceiver dev/manualprecond/WatchService \
         dev/manualprecond/Precond hmgapi/client/HmgApi; do
    check "built: classes.dex has $c" yes "$(grep -q "L$c;" $T/classes.dex && echo yes)"
done

# the mod as HMG-API installs it: the folder with the built APK (and its icons)
mkdir -p $T/mod
cp -r "$HERE" $T/mod/manual-precondition
rm -rf $T/mod/manual-precondition/app && mkdir -p $T/mod/manual-precondition/app && cp "$APK" $T/mod/manual-precondition/app/

# signed with a throwaway key, as a package from an author HMG-API doesn't know yet
java -cp "$ENGINE" hmgapi.engine.Signing keygen $T/test.key >/dev/null
java -cp "$ENGINE" hmgapi.engine.Signing sign $T/mod/manual-precondition $T/test.key >/dev/null

mkdir -p $R/system $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\n' > $R/props.txt

# A US Ioniq 6 on 260903 (the car this mod is for). mod.json "system" is checked against
# HMGAPI_PRODUCT at install (the SDK's HMGAPI_TEST_ANY_CAR only skips the hook map; it has no hooks).
export HMGAPI_PRODUCT=CEEV.USA.0000.V016.010.260903
eng install $T/mod/manual-precondition; check "install on an Ioniq 6 260903" 0 $rc
has "... installed" "installed manual-precondition 0.1.0" "$out"
eng plan; check "plan" 0 $rc
eng apply; check "apply" 0 $rc
has "published as on" "1 of 1 mods on" "$out"
has "in the state the app reads" '"manual-precondition"' "$(cat $R/data/hmgapi/public/state.json)"
printf '{"tune_hold": "toggle", "wheel_star_hold": "open"}\n' > $R/data/hmgapi/settings/manual-precondition.json
eng apply; check "a button setting change applies" 0 $rc
eng remove manual-precondition; check "remove" 0 $rc
eng apply; check "... and apply after it" 0 $rc
check "gone from the mods folder" "" "$(ls $R/data/hmgapi/mods)"

# Not offered on a car that isn't an E-GMP EV (a Telluride)
export HMGAPI_PRODUCT=ONPE.USA.0000.V016.010.260903
eng install $T/mod/manual-precondition; check "install refused on a Telluride" 3 $rc
has "... with the reason" "isn't for this head unit" "$out"
check "... and not installed" "" "$(ls $R/data/hmgapi/mods 2>/dev/null)"
exit $fail
