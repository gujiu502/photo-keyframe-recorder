# 2026-10-01 改進方案實作

依新增設計第 192–276 節實作：版本化協議、Google 身分與 Drive 授權、背景斷點备份、Play/Direct 更新。沿用 Material 3 中文介面。

錄音與本地資料優先；上傳及更新失敗不能刪除原檔。所有入口（包括通知、捷徑及小工具）都受首次設定及強制更新限制。更新只在錄音閒置時提交。

Google Cloud OAuth 客戶端及 Drive API 啟用是外部設定；未配置不得偽造登入或聲稱實測雲端成功。遠端還原屬文件指定的後續 V1.1，這一輪不加入。

## 已完成

- Room 8 → 9 保留旧資料，增加課程名稱、Google 帳號綁定、備份與每個檔案的持久化進度。
- Google 身分登入與 `drive.file` 授權分開處理；既有授權可離線錄音，授權失效只停止備份。
- WorkManager 唯一任務、自動排隊、原檔串流、Drive 斷點續傳、SHA-256 驗證、可恢復授權／空間不足狀態及明確的雲端刪除確認。
- Direct 更新下載、雜湊及相同簽名檢查、原生 PackageInstaller；Play 分開建置並使用官方 In-App Updates。
- 錄音、暫停、保存期間禁止提交安裝，首次設定或強制更新期間仍可查看和導出本地資料。

## 2026-10-01 本機驗證

Direct debug／正式簽名 APK、Play AAB、Release lint 及 JVM 檢查通過。Play 合併 manifest 不含 APK 側載權限。正式 APK 沿用 v0.1.0 的簽名。

Android 11：七個資料庫／migration 測試，以及 Drive 協議測試通過。協議測試以模擬 HTTPS 伺服器執行實際用戶端：重試 20 次只建立一個根目錄；音訊中途斷線後重新查詢 offset、續傳並驗證全部位元組；完成後重試不重傳；空間不足保留原件。這不代表真實 Google Drive 已驗證。

更新測試通過：錯誤 SHA-256、錯誤簽名、錯誤版本均拒絕；ACTIVE／PAUSED／FINALIZING 延後安裝；閒置時提交原生安裝並要求 Android 確認。取消確認後安裝鎖已清除，版本仍為 2。

正式簽名 v0.1.0 → v0.2.0 覆蓋升級通過：MediaStore 的既有錄音列、369397 bytes 原始音訊、三張照片及時間軸不變。未配置 Google 的首次設定畫面仍可進入本地列表，透過文件選擇器重新导出 ZIP，CRC、音訊與照片位元組均通過。

GitHub Android 14 的資料庫、模擬續傳、簽名及離線授權檢查通過；ZIP UI 流程也已驗證。後續重新錄音的測試曾被系統 heads-up 通知遮住拍照按鈕，已用失敗截圖與 camera 日誌確認沒有打開相機。CI 的臨時裝置關閉橫幅弹窗，仍保留前景錄音通知；正式 App 及使用者模擬器不改這項設定。

最終 [GitHub 建置檢查](https://github.com/gujiu502/photo-keyframe-recorder/actions/runs/36872509191) 與 [完整 Android 14 設備流程](https://github.com/gujiu502/photo-keyframe-recorder/actions/runs/36872508613) 均通過，包含相機、暫停拍攝、背景返回、檔名日期時間、ZIP 位元組驗證及異常退出恢復。Play 最終 AAB 也已本機建置通過；離開更新畫面時不會清除進行中的安裝鎖。

已授權離線情境的完整 UI 測試通過：實際 CameraX 拍攝三張照片（兩張在暫停中）、背景返回、檔名自動日期時間、Android 文件選擇器導出 ZIP及強制停止恢復。ZIP 音訊 383594 bytes 與原件一致，三張 JPEG、時間軸、Markdown 及 CRC 均通過。測試帳號僅由 instrumentation 設定，正式 App 沒有跳過 Google 登入的入口。

## 尚待外部設定／驗證

尚未取得 Google OAuth Web client ID，也未驗證 Android OAuth 客戶端設定。真實 Google 登入、Drive 授權、上傳／撤銷／重新授權及 Google Play 更新仍待實測；詳見 [設定說明](GOOGLE_SETUP.md)。缺少配置時不發布 v0.2.0 更新，現有 v0.1.0 APK 繼續提供下載。

兩小時連續錄音與 100 張真實 CameraX 照片的壓力測試、實體手機的來電／低儲存空間情境仍待驗證。遠端還原依設計留到 V1.1。
