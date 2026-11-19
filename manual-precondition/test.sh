#!/bin/sh
# Off-car test of the manual-precondition mod with the HMG-API mod SDK (after
# mods/examples/hello/test.sh in HMG-API). HMG-API's mod workflow runs it with HMGAPI_SDK
# (the unpacked SDK) and HMGAPI_MOD_BUILD (this mod as bin/build-mod.sh built it):
#   HMGAPI_SDK=<sdk> HMGAPI_MOD_BUILD=<built mod> sh manual-precondition/test.sh
# What it checks: the APK holds the app and HMG-API's client; the engine plans, applies and
# removes the mod on a US Ioniq 6 260903; it refuses it on a car that isn't an E-GMP EV.
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

mkdir -p $R/system $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\n' > $R/props.txt
install_mod $T/mod/manual-precondition

# A US Ioniq 6 on 260903 (the car this mod is for). The SDK's HMGAPI_TEST_ANY_CAR only skips the
# hook map (this mod has no hooks); mod.json "system" is still checked against HMGAPI_PRODUCT.
export HMGAPI_PRODUCT=CEEV.USA.0000.V016.010.260903
eng plan; check "plan on an Ioniq 6 260903" 0 $rc
eng apply; check "apply" 0 $rc
has "published as on" "1 of 1 mods on" "$out"
has "in the state the app reads" '"manual-precondition"' "$(cat $R/data/hmgapi/public/state.json)"
printf '{"tune_hold": "toggle", "wheel_star_hold": "open"}\n' > $R/data/hmgapi/settings/manual-precondition.json
eng apply; check "a button setting change applies" 0 $rc
eng remove manual-precondition; check "remove" 0 $rc
eng apply; check "... and apply after it" 0 $rc
check "gone from the mods folder" "" "$(ls $R/data/hmgapi/mods)"

# Not offered on a car that isn't an E-GMP EV (a Telluride)
install_mod $T/mod/manual-precondition
export HMGAPI_PRODUCT=ONPE.USA.0000.V016.010.260903
eng plan
has "refused on a Telluride" "manual-precondition: " "$out"
exit $fail
