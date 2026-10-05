# Google OAuth 設定與新版發布

首次 Google 登入及 Drive 備份需要你管理的 Google Cloud 專案。APK 不含 client secret，也不需要開發者伺服器。

本專案使用 Google Cloud `photo-510704`（專案號碼 `64305210130`）。正式簽名 APK 的真實登入與 Drive 備份已通過；2026-10-05 專案管理員已確認在 Google Console 發布應用。Google 受眾正式發布與品牌驗證是另外的控制台步驟。

品牌配置的公開文件：

- 應用首頁：`https://gujiu502.github.io/photo-keyframe-recorder/`
- 隱私政策：`https://gujiu502.github.io/photo-keyframe-recorder/privacy.html`
- 使用條款：`https://gujiu502.github.io/photo-keyframe-recorder/terms.html`
- 支援與開發者聯絡信箱：`gujiu502@gmail.com`
- 網域：`gujiu502.github.io`。如果 Google 要求驗證擁有權，需由此 Cloud 專案的擁有者或編輯者在 Google Search Console 完成；網站可加入 Google 提供的驗證 HTML 檔或 meta 標籤，不能以 GitHub 登入代替 Google 網域驗證。

在 [品牌頁](https://console.cloud.google.com/auth/branding?project=photo-510704) 保存所需設定，再至 [受眾頁](https://console.cloud.google.com/auth/audience?project=photo-510704) 發布應用；完成的判準是頁面顯示「正式發布／In production」。若控制台仍要求補齊配置或品牌驗證，依實際錯誤處理，不將「已保存」當作「已發布」。

1. 在 Google Cloud 啟用 Google Drive API，填寫 Google Auth Platform 的品牌、支援信箱、隱私政策及使用條款。
2. 建立 **Web 應用程式** OAuth 客戶端。客戶端 ID 是公開識別值，以 `.apps.googleusercontent.com` 結尾；本 App 不使用 client secret。
3. 建立 Android OAuth 客戶端，依下表填寫包名及 SHA-1。
4. 宣告 `https://www.googleapis.com/auth/drive.file`，測試狀態時加入實際登入的測試帳號。公開使用前完成 Google Console 要求的發布／驗證。
5. 設定 GitHub Repository variable `GOOGLE_WEB_CLIENT_ID`；本地建置可設定同名環境變數，或傳入 Gradle `-PGOOGLE_WEB_CLIENT_ID=...`。
6. 先用正式簽名 APK 實測 Google 登入、Drive 授權、上傳、斷網續傳及重新授權，再發布新版。

| 版本 | 包名 | SHA-1 |
|---|---|---|
| Direct release | `com.gujiu502.lectureframe` | `C6:21:1F:1C:29:0D:A5:26:AD:22:ED:C8:DE:D9:D6:95:F0:25:70:7A` |
| 本機 debug | `com.gujiu502.lectureframe.debug` | `43:19:77:57:02:A1:C7:BD:5E:3F:64:8D:74:E7:2E:DD:A7:1F:ED:7C` |
| Google Play | `com.gujiu502.lectureframe` | 使用 Play Console 的 App Signing 憑證 SHA-1，可能與本機 release 不同 |

正式 signing certificate SHA-256：`56DC42F666E5B56247C0E24A81B97C1474DE54DAF2CAD133B90CA63F5E4DA4C4`。

未配置 client ID 的測試版會明確顯示配置缺失，不會假裝登入，也不會允許開始新的正式錄音。發布 workflow 會拒絕發布缺少配置的新版。

Direct 發布包含簽名 APK、`SHA256SUMS.txt` 和 `update.json`。Stable 更新排除 GitHub prerelease；需要接收預覽版時，在「雲端備份與更新」勾選測試版更新。Play 版本不含 APK 側載權限，使用 Google Play 更新。

官方文件：[Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)、[AuthorizationClient](https://developer.android.com/identity/authorization)、[Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)、[斷點上傳](https://developers.google.com/workspace/drive/api/guides/manage-uploads)、[Play Updates](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java)、[PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams)。
