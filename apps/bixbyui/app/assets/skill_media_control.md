---
id: media_control
name: 音乐与媒体控制
description: 放歌、暂停、下一首、调媒体音量、放播客 / 视频
priority: 50
budget: 520
paths: [agent]
triggers:
  keywords: [放歌, 播放, 听歌, 放首, 暂停, 下一首, 上一首, 继续播放, 音乐, 歌单, 播客, 网易云, qq音乐, 酷狗]
  regex: ["(放|播|来)(一)?首.{0,12}", "(切|换)(一)?首"]
  intents: [media]
  packages: [com.netease.cloudmusic, com.tencent.qqmusic, com.kugou.android, tv.danmaku.bili]
  examples: [放一首周杰伦的歌, 下一首, 暂停音乐, 声音调大一点]
requires:
  tools: [volume.media.set, volume.media.up, app.launch, phone.operate, gui.unfold]
---
1. 音量走接口：`volume.media.up` / `down` / `set` / `mute`，读回 `volume.media.get`。
2. 暂停 / 下一首 / 继续：没有媒体键接口。音乐 App 在前台 → `gui.unfold` → `phone.operate` 点播放控件；不在前台就说明，别乱开 App。
3. 点歌：用户点名 App 用那个，没点名 `ask_user` 问用哪个（不擅自选）；`app.launch` 打开后 `phone.operate` 搜歌并点播放。
4. 会员 / 付费曲目弹窗：停下告诉用户，不替用户开会员。
