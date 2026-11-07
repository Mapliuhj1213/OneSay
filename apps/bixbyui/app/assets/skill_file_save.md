---
id: file_save
name: 保存文件与图片
description: 保存图片、存截图、存文件、把内容存到笔记
priority: 45
budget: 480
paths: [agent]
triggers:
  keywords: [保存图片, 存图, 保存到相册, 下载图片, 保存文件, 存下来, 存一下, 下载这个, 另存]
  regex: ["(保存|存|下载)(一下)?(这|那)?(张|个|份)?(图|图片|照片|文件|视频)"]
  packages: [com.sec.android.app.myfiles, com.sec.android.gallery3d]
  examples: [把这张图片保存下来, 把屏幕上的图存到相册, 把这段文字存到笔记]
requires:
  tools: [ui.screenshot, notes.new.text, phone.operate, gui.unfold]
---
1. 文字内容：`notes.new.text` 存进三星笔记（内容照原文，不改写）。
2. 整屏：`ui.screenshot` 存进相册最快。
3. 页面里的单张图 / 文件：在当前 App 里长按 → 「保存图片 / 下载」，用 `gui.unfold` → `phone.operate` 做，goal 写清是哪张图。
4. 来路不明的 apk / 安装包不下载；大文件先说大小。
5. 答复说存在哪（相册 / 笔记 / 下载目录）。
