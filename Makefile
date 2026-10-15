# Recipe Catcher — Build & Developer Automation Makefile
# Automatically load local .env if present and export variables for Gradle and child processes
ifneq (,$(wildcard .env))
    include .env
    export
endif

GRADLEW := ./gradlew
ADB := adb

# Device serial override (optional, e.g. make install-release SERIAL=RZCY40K03JR)
ifdef SERIAL
    ADB_CMD := $(ADB) -s $(SERIAL)
else
    ADB_CMD := $(ADB)
endif

.PHONY: help setup-env verify test check build build-debug build-release build-wear-debug build-wear-release build-all install-debug install-release install-wear-debug install-wear-release clean

help:
	@echo "Recipe Catcher — Available Make Targets:"
	@echo ""
	@echo "  Environment & Setup:"
	@echo "    make setup-env             Copy .env.example to .env if .env does not exist"
	@echo ""
	@echo "  Verification & Quality:"
	@echo "    make verify                Verify :core module JVM isolation (zero Android dependencies)"
	@echo "    make test                  Run all unit and Robolectric test suites (:core, :app, :wear)"
	@echo "    make check                 Run complete verification suite (verify + test)"
	@echo ""
	@echo "  Build Targets:"
	@echo "    make build                 Build phone debug APK (:app:assembleDebug)"
	@echo "    make build-release         Build signed phone release APK using .env credentials (:app:assembleRelease)"
	@echo "    make build-wear-debug      Build Wear OS debug APK (:wear:assembleDebug)"
	@echo "    make build-wear-release    Build signed Wear OS release APK using .env credentials (:wear:assembleRelease)"
	@echo "    make build-all             Build phone and wear debug APKs"
	@echo ""
	@echo "  Device Deployment (adb):"
	@echo "    make install-debug         Install phone debug APK on connected device (adb install -r)"
	@echo "    make install-release       Install phone release APK on connected device (adb install -r)"
	@echo "    make install-wear-debug    Install watch debug APK on connected device (adb install -r)"
	@echo "    make install-wear-release  Install watch release APK on connected device (adb install -r)"
	@echo "    (Optionally append SERIAL=<device_id> to target a specific adb device)"
	@echo ""
	@echo "  Maintenance:"
	@echo "    make clean                 Clean Gradle build artifacts"

setup-env:
	@if [ ! -f .env ]; then \
		cp .env.example .env; \
		echo "Created .env from .env.example. Update .env with your release keystore credentials."; \
	else \
		echo ".env already exists."; \
	fi

verify:
	$(GRADLEW) verifyCoreIsolation

test:
	$(GRADLEW) test

check: verify test

build: build-debug

build-debug:
	$(GRADLEW) :app:assembleDebug

build-release:
	$(GRADLEW) :app:assembleRelease

build-wear-debug:
	$(GRADLEW) :wear:assembleDebug

build-wear-release:
	$(GRADLEW) :wear:assembleRelease

build-all:
	$(GRADLEW) assemble

install-debug:
	$(ADB_CMD) install -r app/build/outputs/apk/debug/app-debug.apk

install-release:
	$(ADB_CMD) install -r app/build/outputs/apk/release/app-release.apk

install-wear-debug:
	$(ADB_CMD) install -r wear/build/outputs/apk/debug/wear-debug.apk

install-wear-release:
	$(ADB_CMD) install -r wear/build/outputs/apk/release/wear-release.apk

clean:
	$(GRADLEW) clean
