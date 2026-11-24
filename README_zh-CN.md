# 快传

快传是一个借助 Wi-Fi P2P，在两台 Android 设备间传递文件或者聊天的应用。

底层使用 [ZevHub](https://github.com/hongui/ZevBond)：一个与链路无关的 P2P 传输库，
在一条会话上多路复用消息、请求、大块传输与有序流。Wi-Fi Direct 只负责提供链路，
协议、分帧和会话管理都在库里，因此本应用只承担界面与业务规则。

# 能力

- 通过 Wi-Fi Direct 发现附近设备并连接。
- 从队列逐个发送文件，带进度节流，并在下一次连接时继续未完成的传输。
- 聊天与文件传输共用同一条会话，不再单独开一个端口。

# 使用的技术

1. 自定义View[LoadingView](app/src/main/java/com/mob/lee/fastair/view/LoadView.kt)
2. 自定义CoordinatorLayout布局的behavior[TranslationBehavior](app/src/main/java/com/mob/lee/fastair/view/TranslationBehavior.kt)
3. 由 [Connection](app/src/main/java/com/mob/lee/fastair/base/Connection.kt) 持有的单一共享会话
4. Kotlin协程的使用
5. Kotlin代码示例
6. Jetpack代码示例
7. 其他有意思的东西
8. ZevHub 传输层（以 AAR 形式依赖）

# APK

[只需要个APK的安装包.](https://github.com/hongui/FastAir/releases)

# 下载

[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
     alt="Get it on IzzyOnDroid"
     height="70">](https://apt.izzysoft.de/fdroid/index/apk/com.mob.lee.fastair)

或者到[发布页面](https://github.com/hongui/FastAir/releases/latest)下载最新的安装包.

# 页面预览

<img width="25%" src="Screenshots/discover.png" alt="discover page" /><img width="25%" src="Screenshots/list.png" alt="home list page" /><img width="25%" src="Screenshots/chat.png" alt="chat list page" /><img width="25%" src="Screenshots/file.png" alt="transmission page" />
