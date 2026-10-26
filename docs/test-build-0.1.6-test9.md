# 0.1.6-test9 — 修正并缩窄悬浮 Dock

原test8把圆角Dock放在Scaffold.bottomBar，整条底栏仍占位，滚动视口在Dock上方被横向截断。

现在Dock为外层Box中的独立叠层，Scaffold不再预留整条bottomBar，内容可延伸至Dock背后。仅5个tab使用LocalDockClearance=88dp（68dp高度+20dp上下外距），ScrollBody追加可滚动尾部留白，让最后一项完整滚至Dock上方。固件导入浮动按钮同步抬高，详情/结果页不增加留白。

按用户后续要求，左右外边距由16dp增至32dp，最大宽度520dp降至460dp；保留5个导航入口和原点击高度。

验证：assembleDebug、lintDebug通过，独立布局审查通过。Xiaomi手机无线ADB升级成功；主工作台滚动到底，统计卡片全部内容完整露出并位于Dock上方（本地截图workbench-bottom.png），缩窄后截图dock-narrow.png确认。未改烧录算法，未重复硬件擦写。其他tab共享ScrollBody；固件页按钮上移通过代码审查，未单独完成截图验证。

APK：dist/Corn-Flashing-0.1.6-test9.apk，versionCode16。
SHA-256：c94133af5818e60e95b3d8c388c73c9d95af2f3669f275f4f5b84b37be290683
