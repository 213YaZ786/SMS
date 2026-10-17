#!/bin/bash
# usage: maestro/run-screening.sh [adb serial]
# Unknown senders screened (iOS 26 Messages' Unknown Senders, https://support.apple.com/en-us/125068;
# checkers ported from SpamBlocker, https://github.com/aj3423/SpamBlocker): seeds one message of each
# kind from numbers in no contact through the debug build's DebugBulkReceiver, then runs screening.yaml
# (https://docs.maestro.dev). Needs the debug build, SMS as the default SMS app, screening on.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.sms.debug
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

# Nothing learned as spam (run-spam.sh trains the classifier): the app stopped, its spam file removed.
"${adb[@]}" shell am force-stop $pkg
"${adb[@]}" shell run-as $pkg rm -f files/spam.json

# The body goes through adb's shell once: double quotes keep it whole, no quote left in the text.
seed() { "${adb[@]}" shell "am broadcast -n $pkg/com.yaz.sms.DebugBulkReceiver --es from '$1' --es body \"$2\"" >/dev/null; sleep 1; }
seed +33799000111 "Soldes -50% sur tout le site. STOP au 36180"
seed COLISSIMO "Votre colis 1Z999AA10123456784 est en livraison"
seed GOOGLE "G-482913 est votre code de verification Google"
seed +33788776655 "Salut, c est Paul"

"${adb[@]}" shell input keyevent HOME
maestro ${1:+--device "$1"} test "$here/screening.yaml"
