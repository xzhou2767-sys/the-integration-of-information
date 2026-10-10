# 分支项目：微信视频号接入

> **定位**：独立分支项目。2026-09-25 依整改决策（E21）从 `docs/PROJECT_SYSTEM.md`
> 第十一节移出，原 §11.1 的微信视频号行与 §11.2 整节内容在此留档。
>
> **本文件不属于信息聚合主线的演进路线**，不纳入主文档的「待评估」清单；
> 是否立项、何时立项由独立决策。主线相关结论（如 `downloadVideo` 需扩展）
> 仍适用于其他 HLS 来源，故保留技术细节。

---

## 一、结论：观众侧 Web 无入口（实测）

服务端对微信视频号观众侧 `/web/pages/*` 路径返回 `errCode 10012 非法请求`；
所有路径 fallback 到创作者后台 SPA；使用微信 UA 无差异。

**因此：作为「信息源」的观众侧采集不可行**，仅创作者后台可看**自己账号**的回放。

## 二、可行路径：创作者后台（实测接口留档）

若将来立项，以下为已实测的接口路径：

```
页面：https://channels.weixin.qq.com/platform/live/liveReplayHistory
微前端：https://channels.weixin.qq.com/micro/live/liveReplayHistory
API 前缀：https://channels.weixin.qq.com/micro/live/cgi-bin/mmfinderassistant-bin
回放列表：POST /live/get_live_replay_list      → liveObjectList[]
回放详情：POST /live/live_replay_info           → 入参 objectId
视频地址：POST /live/get_live_replay_url        → replayHlsUrl / sourceH264HlsUrl
账号标识：finderUsername（v2_xxx@finder）；多账号列表 POST /auth/auth_finder_list
扫码状态：window.wx_errcode（408 等待 / 404 已扫待确认 / 405 已确认 / 403 已失效）
```

**分享链接鉴权**：靠 `exportkey` 鉴权，仅微信内可用——意味着分享链接无法在云端
无头浏览器中直接复用，需在微信客户端环境内取得。

## 三、技术阻碍（立项前须解决）

> 回放为 **HLS(m3u8) + 防盗链**；现有 `VideoAnalysisService.downloadVideo` 为裸 GET
> 无 Header，**需扩展**才能下载（须补 Referer/UA/防盗链头，以及 HLS 分片合并）。

其余相关能力缺口见主文档 §十一 与 `docs/video-milestones.md` M1（来源解析）。

## 四、与主线的边界

- **不进入** `Taxonomy.SourcePlatform`：它是编译期枚举，且主线按 `platform-onboarding`
  清单接入需配套适配器、刮板、`BROWSER_PRIMARY`（范围表）、`isLowValue`、质量分五处登记。
  视频号若接入，形态更接近「自有账号内容导出」而非「公开信息源聚合」。
- **不进入** 主文档 §十、§十一 的路线与限制清单；相关结论只在本文件维护。
- 主线中与之相邻的通用能力（HLS 下载、真实时长探测 B07）在 `docs/video-milestones.md`
  的 M1/M2 中独立跟踪，不受本项目影响。

---

*移出日期：2026-09-25｜依据：整改方案 `.trae/documents/remediation-plan-2026-09-25.md` 的 E21 决策*
