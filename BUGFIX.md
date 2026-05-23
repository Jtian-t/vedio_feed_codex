# Bugfix Notes

## Case 1: Offline Kotlin Plugin Resolution

Investigation: initial offline Gradle run could not resolve `org.jetbrains.kotlin.android`. Root cause: cached metadata was incomplete for offline plugin resolution. Fix: reran dependency resolution with normal Gradle access.

## Case 2: AGP Gradle Version Mismatch

Investigation: AGP 9.2.1 failed under Gradle 8.14. Root cause: AGP requires Gradle 9.4.1. Fix: updated wrapper metadata and used the cached Gradle 9.4.1 distribution.

## Case 3: Kotlin Extension Collision

Investigation: applying both AGP 9.2.1 and explicit Kotlin Android plugin caused a duplicate `kotlin` extension. Root cause: local AGP/Kotlin integration already registers the extension. Fix: removed explicit Kotlin plugin from the project plugin block.
