#!/bin/bash
# usage: maestro/run-media.sh [adb serial]
# Media from outside, replayed (OWASP MASVS-CODE-4, https://mas.owasp.org/MASVS/controls/MASVS-CODE-4/):
# the files of Modules/tools/media-samples.sh, honest and hostile, seeded by the debug build's
# DebugBulkReceiver from a known and an unknown number, then checked by Maestro flows
# (https://docs.maestro.dev). Needs the debug build installed and SMS as the default SMS app.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.sms.debug
known=+33612345678      # a contact on both emulators
stranger=+33799887766   # in no contact
samples=/tmp/sms-media-samples
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

[ -f "$samples/cut.mp4" ] || "$here/../../../Modules/tools/media-samples.sh" "$samples" >/dev/null
for f in "$samples"/*; do
  n=$(basename "$f")
  "${adb[@]}" push "$f" "/data/local/tmp/$n" >/dev/null
  "${adb[@]}" shell "run-as $pkg sh -c 'mkdir -p files/t && cat /data/local/tmp/$n > files/t/$n'"
done

# One message per sample: its part under the type its sender claims, and a body that names it.
seed() { "${adb[@]}" shell am broadcast -n $pkg/com.yaz.sms.DebugBulkReceiver --es from "$1" --es file "files/t/$2" --es mime "$3" --es body "'$4'" >/dev/null; sleep 1; }
seed $known landscape.mp4 video/mp4 T-landscape
seed $known cut.mp4 video/mp4 T-cut
seed $known lie-text-as.jpeg image/jpeg T-fake
seed $known card.vcf text/x-vcard T-card
seed $stranger photo.jpg image/jpeg T-held-photo
seed $stranger song.mp3 audio/mpeg T-held-sound

open() { "${adb[@]}" shell am start -a android.intent.action.SENDTO -d "smsto:$1" -p $pkg >/dev/null; sleep 3; }
status=0
open $known; maestro ${1:+--device "$1"} test "$here/media-known.yaml" || status=1
open $stranger; maestro ${1:+--device "$1"} test "$here/media-unknown.yaml" || status=1
"${adb[@]}" shell settings put system user_rotation 0
exit $status
