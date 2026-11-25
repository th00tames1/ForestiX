# Publishing an APK visible in Settings

Increment `versionCode` in `app/build.gradle.kts` for every distributed APK.
Keep the application ID and channel's signing certificate unchanged so users
can update without uninstalling. The marketing version can stay the same.

| APK | Release tag | Uploaded asset name |
| --- | --- | --- |
| Work / test (debug variant) | `android-work-build-N` | `ForestiX-Android-work-build-N.apk` |
| Stable (release variant) | `android-build-N` | `ForestiX-Android-build-N.apk` |

Replace `N` with the APK's actual numeric version code. Target the exact tested
commit and publish the work release as a prerelease, the stable release as a
non-prerelease. Settings checks published releases only when requested. It
offers the highest newer code on the installed channel with a matching APK;
older unnumbered tags aren't upgrade candidates. APKs predating this feature
need one manual update from GitHub Releases.
