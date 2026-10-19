#!/bin/bash
# usage: maestro/run-spam.sh [adb serial]   (an emulator: the notification check sends a text through its console)
# Spam (Google Messages' Report spam / Not spam, https://support.google.com/messages/answer/9061432;
# classifier ported from SpamBlocker, https://github.com/aj3423/SpamBlocker): seeds conversations from numbers
# in no contact through the debug build's DebugBulkReceiver, runs spam.yaml
# (https://docs.maestro.dev), then checks with a text sent through the emulator's modem that spam does not
# notify and a person does. Needs the debug build, SMS as the default SMS app, Spam protection on.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.sms.debug
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

# A run starts with nothing learned: the app is stopped, its spam file removed.
"${adb[@]}" shell am force-stop $pkg
"${adb[@]}" shell run-as $pkg rm -f files/spam.json

# The body goes through adb's shell once: double quotes keep it whole, no quote left in the text.
seed() { "${adb[@]}" shell "am broadcast -n $pkg/com.yaz.sms.DebugBulkReceiver --es from '$1' --es body \"$2\"" >/dev/null; sleep 1; }
# Five texts of a seller, five of a friend: ten examples once both are labelled.
seed +33799000222 "Vous avez gagne un prix, cliquez ici pour reclamer"
seed +33799000222 "Felicitations, cadeau gratuit, cliquez ici maintenant"
seed +33799000222 "Offre exclusive: gagnez un prix gratuit, cliquez ici"
seed +33799000222 "Urgent: reclamez votre prix gratuit sur bit.ly/prix"
seed +33799000222 "Gagnez un cadeau gratuit, cliquez maintenant"
seed +33788776655 "Salut, c est Paul"
seed +33788776655 "On se voit demain a 18h ?"
seed +33788776655 "Merci pour hier soir, c etait super"
seed +33788776655 "Tu peux m appeler ce soir ?"
seed +33788776655 "A demain, bonne nuit"
# Two senders never labelled: one that reads like the seller, one like the friend.
seed +33799000333 "Cliquez ici pour reclamer votre prix gratuit maintenant"
seed +33766554433 "Salut, tu peux m appeler ce soir"

"${adb[@]}" shell input keyevent HOME
maestro ${1:+--device "$1"} test "$here/spam.yaml"

# The seller stays reported: a new text from it raises no notification, one from a person does.
"${adb[@]}" shell input keyevent HOME
"${adb[@]}" emu sms send +33799000222 "Nouvelle offre gratuite pour vous" >/dev/null
"${adb[@]}" emu sms send +33755443322 "Bonjour, c est Marie" >/dev/null
sleep 4
notifications="$("${adb[@]}" shell dumpsys notification --noredact)"
if grep -q "Nouvelle offre gratuite" <<<"$notifications"; then echo "FAILED: spam notified"; exit 1; fi
if ! grep -q "Bonjour, c est Marie" <<<"$notifications"; then echo "FAILED: a person did not notify"; exit 1; fi
echo "spam: flow passed, spam quiet, person notified"
