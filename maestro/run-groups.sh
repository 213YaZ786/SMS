#!/bin/bash
# usage: maestro/run-groups.sh [adb serial]
# Groups and mentions on one device (Signal's mentions, https://github.com/signalapp/Signal-Android;
# Google Messages' group details, https://support.google.com/messages/answer/16287429): seeds a group
# message from Marie-Christine Delacroix-Beaumont to Joëlle Martin and Valentina Ivanova (the emulator's contacts) through the debug
# build's DebugBulkReceiver, then runs groups.yaml and mentions.yaml (https://docs.maestro.dev). Needs the
# debug build, SMS as the default SMS app. The chat between two phones cannot run on one device.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
adb=(~/Android/Sdk/platform-tools/adb ${1:+-s "$1"})
pkg=com.yaz.sms.debug
export MAESTRO_CLI_NO_ANALYTICS=true MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

# The body goes through adb's shell once: double quotes keep it whole, no quote left in the text.
seed() { "${adb[@]}" shell "am broadcast -n $pkg/com.yaz.sms.DebugBulkReceiver --es from '+33612345678' --es group '0698765432,0123456789' --es body \"$1\"" >/dev/null; sleep 1; }
seed "Group-Seed hello"
seed "@Joëlle Martin lunch?"

"${adb[@]}" shell input keyevent HOME
maestro ${1:+--device "$1"} test "$here/groups.yaml"
maestro ${1:+--device "$1"} test "$here/mentions.yaml"
