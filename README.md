# PortalX Android (v0.3.0)

Native Android client for Portal One / PortalX (portal.pravahax.com). Kotlin + Jetpack Compose (Material 3), minSdk 26, targetSdk 35.

- Build: `./build.sh` → `PortalX.apk`
- Signing: requires `keystore.properties` + `portalx-release.jks` in the repo root. These are deliberately **not** committed. Keep the same key for every release, otherwise updates won't install.
- API notes: see `API_MAP.md`
- Reports: `PortalX-Audit-v0.2.0.pdf`, `PortalX-Value-Report-v0.3.0.pdf`
