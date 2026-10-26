# 0.1.6-test8 — 结果页轮询和界面调整

## 行为

1. 自动换板检测归成功结果页所有：离页、返回、关闭开关、后台、进入批次或新任务时取消。仅结果页前台且开关开启时启动；按jobId记录首次启动后的3分钟期限，暂停/重进不重置。取消后迟到的USB结果不能触发新烧录。一次已提交的同步USB读会在其超时内收尾。
2. 主页面仅消费Scaffold的系统边距，移除重复statusBarsPadding。手机标题文字顶部从y=324变为172，上移152像素，保持状态栏安全区。
3. 五个主导航入口改为圆角悬浮岛，带两侧/底部留白、选中态、边框和阴影；内容为导航及手势区预留空间。
4. 全局notice与固件导入错误统一使用居中MessageDialog。长文本可滚动/选择/复制，按钮关闭；报告页不再重复消费notice或显示Snackbar。识别失败弹窗包含实际错误内容，设备页诊断保留。

## 验证

- 114项单元测试、assembleDebug、lintDebug通过。8项新增NextBoardWatcher测试覆盖取消、超时、重复启动期限、同UID/新UID以及关闭开关等情况。
- 独立审查完成；补充LifecycleEventObserver在ON_PAUSE/ON_STOP同步取消，避免仅依赖重组时后台取消延迟。
- Xiaomi 25060RK16C Android17/API37无线ADB安装，顶部留白/浮岛截图检查通过。
- 手机消息弹窗、复制后“已复制”、关闭验证通过。
- 真实任务#12使用原Template.hex、ST-LINK/V2 V2J23S4、256KB F1：69668字节/35页，擦写、回读和复位通过，17.7秒。
- 成功页检测期间USB GET_VERSION日志计数增加；按Back回工作台后，间隔8秒计数128→128，确认不再持续轮询。3分钟边界使用受控时间单元测试验证，未额外等待3分钟实机测试。
- 截图、用户固件和设备日志仅留本地.local-validation/test8，不提交。

APK：dist/Corn-Flashing-0.1.6-test8.apk，versionCode15。
SHA-256：8323efce1515e2400caef7a23785db5c349bdda46a4ce86a197a1b0e24eb92b9
