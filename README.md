# FastAir

[简体中文](README_zh-CN.md)

An Android app for transferring files and chatting between two Android devices.

It is built on [ZevHub](https://github.com/hongui/ZevBond), a link-agnostic P2P
transport library that multiplexes messages, requests, bulk transfers and
ordered streams over a single session. Wi-Fi Direct provides the link; the
protocol, framing and session layers live in the library, so this app only
carries the UI and the business rules.

# Feature

- Discover nearby devices over Wi-Fi Direct and connect to one.
- Send files from a queue, one at a time, with throttled progress and retry on
  the next connection.
- Chat over the same session instead of a second socket.

# What technology was used?

1. Custom View [LoadingView](app/src/main/java/com/mob/lee/fastair/view/LoadView.kt)
2. Custom CoordinatorLayout behavior [TranslationBehavior](app/src/main/java/com/mob/lee/fastair/view/TranslationBehavior.kt)
3. A single shared session owned by [Connection](app/src/main/java/com/mob/lee/fastair/base/Connection.kt)
4. Kotlin Coroutines
5. Kotlin use case
6. Jetpack use case
7. Other interesting
8. ZevHub transport, consumed as a published AAR

# Download

[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
     alt="Get it on IzzyOnDroid"
     height="70">](https://apt.izzysoft.de/fdroid/index/apk/com.mob.lee.fastair)

Or get the latest APK from the [Releases Section](https://github.com/hongui/FastAir/releases/latest).

# Preview

<img width="25%" src="Screenshots/discover.png" alt="discover page" /><img width="25%" src="Screenshots/list.png" alt="home list page" /><img width="25%" src="Screenshots/chat.png" alt="chat list page" /><img width="25%" src="Screenshots/file.png" alt="transmission page" />
