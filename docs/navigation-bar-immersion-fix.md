# 系统手势区域沉浸修复

审查范围：`bbc58f0` 的底部留白修改，以及当前 Activity、主题和主页面的系统栏处理。

原实现的 Scaffold 默认已经预留导航栏高度，同时 LocalDockClearance 又增加该高度，造成重复避让，滚动页面也止于系统导航栏上方。现在 Scaffold 只处理顶部和横向安全区，页面及其背景延伸至屏幕底部；悬浮 dock 仍保留 navigationBarsPadding，滚动尾距仍为 dock 高度、外间距和一次导航栏高度，保证最后一项可滚至 dock 上方。

主题和 Activity 显式关闭系统栏对比度蒙层；导航栏分隔线透明，窗口背景与 Compose 背景统一为 #121212。保留系统手势条及浅色系统图标。

验证：独立代码审查通过；`:app:assembleDebug :app:lintDebug --offline` 通过，lint 0 errors。此改动不涉及烧录引擎或保留区配置。

真机视觉验证待完成：当前无线 ADB 离线且没有发现 mDNS 设备，未安装或截取修复后的真机画面。不能据此声称小米系统上的视觉问题已经实测关闭。

模拟器补充验证：Pixel 9 Pro / API 35 已安装修复版并打开工作台，截图显示手势条区域与页面背景连通，悬浮 dock 保持安全间距。已切换为带窗口的模拟器供用户查看；小米真机仍未验证。

发布 APK：`dist/Corn-Flashing-0.1.7.apk`（0.1.7 / versionCode 21，沿用 0.1.6 的 debug 签名），旁附 SHA-256 文件。

实现依据：[Android 系统栏保护](https://developer.android.com/develop/ui/compose/system/system-bars)、[Material 3 系统窗口边距](https://developer.android.com/develop/ui/compose/system/material-insets)。
