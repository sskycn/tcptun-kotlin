# TcpTun Google Play 商店素材

本目录用于 Google Play Console 的商店页和审核准备。

## 可直接上传

- `app-icon-512.png`：512×512 应用图标
- `feature-graphic-1024x500.png`：1024×500 Feature graphic
- `feature-graphic-upload-2048x1000.png`：给 Play Console 裁剪器使用的 2× Feature graphic 上传源图
- `screenshots/zh/`：中文界面截图
- `screenshots/en/`：英文界面截图
- `screenshots/play/en/`：符合 Play 9:16 上传比例的英文手机截图
- `screenshots/play/zh/`：符合 Play 9:16 上传比例的中文手机截图

## 文案和审核资料

- `store-listing-zh-CN.md`：中文商店文案、审核步骤、VPN 声明草稿
- `store-listing-en-US.md`：英文商店文案、审核步骤、VPN 声明草稿
- `privacy-policy-draft-zh-CN.md`：中文隐私政策草稿
- `privacy-policy-draft-en-US.md`：英文隐私政策草稿
- `play-console-policy-draft-zh-CN.md`：中文 Play Console / VPN / Data safety 填写辅助稿
- `play-console-policy-draft-en-US.md`：英文 Play Console / VPN / Data safety 填写辅助稿
- `release-notes-v0.2.51-zh-CN.md`：v0.2.51 中文更新说明
- `release-notes-v0.2.51-en-US.md`：v0.2.51 英文更新说明

## 注意事项

1. 隐私政策中的开发者名称和联系邮箱必须替换成真实信息，并通过 HTTPS 公开访问；当前项目没有现成的应用内隐私政策链接入口。
2. TcpTun 不经营任何服务端。政策和商店文案已区分“开发者不收集”与“用户流量会发送到用户配置的远端节点”，不能笼统写成“网络完全不传输数据”。
3. 当前代码包含 Google/Cloudflare 连通性探测，以及用户主动触发的 Google/GitHub/Cloudflare TCPing；这些行为已在政策和提交辅助稿中披露。
4. Data safety 仍需在 Play Console 按最终 Release AAB 逐项填写，特别是第三方远端节点和诊断目标的网络处理，不要只复制“无开发者收集”。
5. 原始截图来自当前 Debug build 的真实模拟器界面；`screenshots/play/` 是按 Play 手机截图要求裁剪为 1080×1920（9:16）的上传版本。
6. 当前每种语言有 3 张截图，已满足至少 2 张的上传要求；如果要参加商店推广，通常还需要准备至少 4 张符合条件的截图。
7. 如果 Play Console 提示 1024×500 图片太小，请上传 `feature-graphic-upload-2048x1000.png`，让裁剪器保留足够余量；不要上传 `feature-graphic-source.png`。
