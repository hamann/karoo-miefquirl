# karoo-miefquirl — everything here assumes you are inside `nix develop`.

ANDROID := android
APK     := $(ANDROID)/app/build/outputs/apk/debug/app-debug.apk

# Without pipefail a failing gradle run is masked by whatever follows it in a
# pipe, and the build silently "succeeds".
SHELL := /bin/bash
.SHELLFLAGS := -o pipefail -c

.PHONY: all test apk install clean tools

all: apk

## test — the domain logic is a plain JVM module, so this needs no emulator,
## no Android SDK, and no GitHub credentials.
test:
	cd $(ANDROID) && gradle :headwind:test

apk:
	cd $(ANDROID) && gradle assembleDebug

## install — sideload onto a Karoo reachable over adb.
#
# Karoo 3 pairs over USB or, once `adb tcpip` has been done, over wifi:
#   adb connect <karoo-ip>:5555
install: apk
	adb install -r $(APK)

## tools — show what the nix shell actually gave us.
tools:
	@echo "ANDROID_HOME  $(ANDROID_HOME)"
	@java -version 2>&1 | head -1
	@gradle --version 2>/dev/null | grep -E '^Gradle' || true
	@adb version | head -1

clean:
	cd $(ANDROID) && gradle clean
